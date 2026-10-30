package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.KbChunkBean;
import com.aiproject.ai.service.bean.KbDocBean;
import com.aiproject.ai.service.gateway.EmbeddingClient;
import com.aiproject.ai.service.gateway.RerankResolver;
import com.aiproject.ai.service.gateway.dto.KbDocUpload;
import com.aiproject.ai.service.gateway.dto.KbSearchItem;
import com.aiproject.ai.service.gateway.dto.KbSearchResult;
import com.aiproject.ai.service.mapper.KbChunkBeanMapper;
import com.aiproject.ai.service.mapper.KbDocBeanMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.db.utils.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 本地知识库（RAG）：文档解析 → 分块 → 向量化 → 相似度检索。
 * <p>
 * 向量以 JSON 文本存 kbchunkbean.embedding，检索时在内存中做余弦相似度排序；
 * 当前实现面向中小规模文档库（万块以内），后续可平滑迁移 pgvector。
 */
@Slf4j
@Service
public class KbRagHolder {

    /** RRF 融合常数。原论文与业界常用 60 */
    private static final int RRF_K = 60;

    private static final int MAX_DOC_CHARS = 200000;
    private static final int MAX_CHUNKS = 500;
    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 100;
    private static final int MAX_TOP_K = 20;
    private static final int MAX_QUERY_CHARS = 2000;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    KbDocBeanMapper<KbDocBean> kbDocBeanMapper;

    @Autowired
    KbChunkBeanMapper<KbChunkBean> kbChunkBeanMapper;

    @Autowired
    EmbeddingClient embeddingClient;

    /** 二阶段精排：可选，未配置时检索仍走混合排序 */
    @Autowired
    RerankResolver rerankResolver;

    /**
     * 文档列表（含分块数与状态），不返回任何向量与正文。
     *
     * <p>归属隔离：只返回当前用户自己上传的文档。
     * 修复前这里用空 Bean 查询，等于拉全表——任意登录用户都能看到
     * 其他人的文档名、分块数与状态。</p>
     */
    public Result<List<KbDocBean>> listDocs(String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(null, "未登录或登录已过期");
        }
        KbDocBean where = new KbDocBean();
        where.setLastuser(uid);
        List<KbDocBean> docs = kbDocBeanMapper.select(null, where);
        docs.sort(new Comparator<KbDocBean>() {
            @Override
            public int compare(KbDocBean a, KbDocBean b) {
                return Long.compare(b.getIid() == null ? 0 : b.getIid(),
                        a.getIid() == null ? 0 : a.getIid());
            }
        });
        return Result.succeed(docs, "查询成功");
    }

    /**
     * 上传文档：服务端分块并向量化后入库。
     * 文本由前端读取后以字符串提交（支持 txt/md 等纯文本）。
     */
    public Result<KbDocBean> uploadDoc(String uid, KbDocUpload data) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(null, "未登录或登录已过期");
        }
        if (data == null || data.getName() == null || data.getName().trim().isEmpty()) {
            return Result.failed(null, "文档名称不能为空");
        }
        String content = data.getContent();
        if (content == null || content.trim().isEmpty()) {
            return Result.failed(null, "文档内容不能为空");
        }
        if (content.length() > MAX_DOC_CHARS) {
            return Result.failed(null, "文档过大，单篇最多 " + MAX_DOC_CHARS + " 字符");
        }
        EmbeddingClient.ResolvedEmbedding embedding = embeddingClient.resolve();
        if (embedding == null) {
            return Result.failed(null, "尚未配置向量模型：请在模型配置中启用 OpenAI 直连并填写向量模型（embedModel）");
        }

        String name = data.getName().trim();
        String kbName = data.getKbName() == null || data.getKbName().trim().isEmpty()
                ? "默认知识库" : data.getKbName().trim();

        List<String> chunks = chunkText(content);
        if (chunks.isEmpty()) {
            return Result.failed(null, "文档解析后没有有效内容");
        }
        if (chunks.size() > MAX_CHUNKS) {
            return Result.failed(null, "文档分块过多（" + chunks.size() + " 块），单篇最多 " + MAX_CHUNKS + " 块");
        }

        List<double[]> vectors;
        try {
            vectors = embeddingClient.embed(embedding, chunks);
        } catch (IllegalStateException e) {
            return Result.failed(null, e.getMessage());
        } catch (Exception e) {
            log.warn("[KbRag] 向量化失败: {}", e.getMessage());
            return Result.failed(null, "向量化失败：" + e.getMessage());
        }

        KbDocBean doc = new KbDocBean();
        doc.setKbName(kbName);
        doc.setName(name);
        doc.setCharCount((long) content.length());
        doc.setChunkCount(chunks.size());
        doc.setStatus("ready");
        doc.setLastuser(uid);
        if (kbDocBeanMapper.install(doc) <= 0) {
            return Result.failed(null, "保存文档失败");
        }

        // HNSP install 不回填 iid，用「同名 + 同库 + 同用户」取最新一条
        Long docIid = findDocIid(kbName, name, uid);
        if (docIid == null) {
            // 拿不到 iid 就无法安全定位刚插入的行（同名旧文档可能已存在），
            // 不能按名称盲删，只能留痕交由运维处理
            log.error("[KbRag] 文档已写入但无法定位 iid，可能存在孤立的文档行, kbName={}, name={}, uid={}",
                    kbName, name, uid);
            return Result.failed(null, "保存文档失败：无法定位新文档");
        }

        for (int i = 0; i < chunks.size(); i++) {
            KbChunkBean chunk = new KbChunkBean();
            chunk.setDocIid(docIid);
            chunk.setKbName(kbName);
            chunk.setChunkIndex(i);
            chunk.setContent(chunks.get(i));
            chunk.setEmbedding(toJson(vectors.get(i)));
            chunk.setEmbeddingModel(embedding.getEmbedModel());
            chunk.setEmbeddingDim(vectors.get(i).length);
            chunk.setTokenCount(chunks.get(i).length());
            chunk.setLastuser(uid);
            if (kbChunkBeanMapper.install(chunk) <= 0) {
                // 用补偿代替事务：文档已在上面提交，分块失败若不回收，
                // 列表会显示一个 chunkCount 正常但实际检索不到的「空壳文档」
                log.warn("[KbRag] 分块写入失败，开始回滚, docIid={}, index={}", docIid, i);
                rollbackDoc(uid, docIid);
                return Result.failed(null, "保存文档失败：第 " + (i + 1) + " 个分块写入失败，已回滚");
            }
        }
        return Result.succeed(doc, "文档已入库，共 " + chunks.size() + " 块");
    }

    /**
     * 入库失败的补偿删除：先删分块再删文档，条件都带归属。
     * 回滚自身失败只记录日志——此时仍要把原始写入失败返回给调用方，
     * 不能因为清理异常改写成另一个错误、掩盖真正原因。
     */
    private void rollbackDoc(String uid, Long docIid) {
        try {
            KbChunkBean chunkWhere = new KbChunkBean();
            chunkWhere.setDocIid(docIid);
            chunkWhere.setLastuser(uid);
            kbChunkBeanMapper.delete(chunkWhere);
            KbDocBean docWhere = new KbDocBean();
            docWhere.setIid(docIid);
            docWhere.setLastuser(uid);
            kbDocBeanMapper.delete(docWhere);
        } catch (Exception e) {
            log.error("[KbRag] 回滚失败，需人工清理 docIid={}: {}", docIid, e.getMessage());
        }
    }

    /**
     * 删除文档（连同分块）。
     *
     * <p>归属隔离：删除条件同时带 iid 与 lastuser，属于他人的文档匹配不到行，
     * 直接返回失败（fail-closed）。修复前只按 iid 删，任意登录用户
     * 知道文档 iid 即可删除他人文档。</p>
     */
    public Result<Boolean> deleteDoc(String uid, KbDocBean data) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(false, "未登录或登录已过期");
        }
        if (data == null || data.getIid() == null) {
            return Result.failed(false, "文档 iid 不能为空");
        }
        KbDocBean where = new KbDocBean();
        where.setIid(data.getIid());
        where.setLastuser(uid);
        if (kbDocBeanMapper.delete(where) <= 0) {
            return Result.failed(false, "删除失败：文档不存在或不属于当前用户");
        }
        KbChunkBean chunkWhere = new KbChunkBean();
        chunkWhere.setDocIid(data.getIid());
        kbChunkBeanMapper.delete(chunkWhere);
        return Result.succeed(true, "删除成功");
    }

    /**
     * 相似度检索：向量路 + 关键词路混合，RRF 融合后取 topK。
     *
     * <p>归属隔离：分块查询同时带 kbName（可选）与 lastuser，只在自己的文档里检索。
     * 修复前只按 kbName 过滤，任意登录用户都能检索并读取他人文档的正文分块——
     * 这是本地 RAG 最严重的泄露面，因为返回内容直接是原文。</p>
     *
     * <p><b>为什么是混合检索</b>：单一向量路有两个结构性盲区——</p>
     * <ul>
     *   <li>专有名词、版本号、SKU、人名：embedding 对这类 token 的区分度很差，
     *       「BGE-M3」和「BGE-M2」的向量可能非常接近；</li>
     *   <li>用户的问法和文档用词不一致时（问「怎么报销」vs 文档写「差旅费用流程」），
     *       纯语义匹配反而可能漏掉关键词完全一致的片段。</li>
     * </ul>
     * <p>反过来纯关键词路又不懂同义词。两路互补，用 RRF（Reciprocal Rank Fusion）
     * 融合排名——RRF 只看排名不看原始分，因此不需要把余弦分和词频归一到同一量纲，
     * 这是它比加权求和更适合这里的原因。</p>
     *
     * <p><b>两路的分工</b>：关键词路用 PostgreSQL 全文检索（GIN 表达式索引）做候选召回，
     * 向量路对全部自有分块算余弦。召回阶段用索引、精排阶段在内存，
     * 与当前「万块以内」的规模匹配。</p>
     */
    public Result<KbSearchResult> search(String uid, String query, String kbName, Integer topK) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(null, "未登录或登录已过期");
        }
        if (query == null || query.trim().isEmpty()) {
            return Result.failed(null, "请输入检索内容");
        }
        if (query.length() > MAX_QUERY_CHARS) {
            return Result.failed(null, "检索内容过长，最多 " + MAX_QUERY_CHARS + " 字符");
        }
        EmbeddingClient.ResolvedEmbedding embedding = embeddingClient.resolve();
        if (embedding == null) {
            return Result.failed(null, "尚未配置向量模型：请在模型配置中启用 OpenAI 直连并填写向量模型（embedModel）");
        }

        // 先确认有没有分块，再向量化问题：空知识库时省掉一次付费的 embedding 调用，
        // 也让「知识库为空」不被向量服务故障掩盖成「检索失败」
        KbChunkBean where = new KbChunkBean();
        if (kbName != null && !kbName.trim().isEmpty()) {
            where.setKbName(kbName.trim());
        }
        where.setLastuser(uid);
        List<KbChunkBean> chunks = kbChunkBeanMapper.select(null, where);
        if (chunks == null || chunks.isEmpty()) {
            KbSearchResult empty = new KbSearchResult();
            empty.setQuery(query.trim());
            empty.setModel(embedding.getEmbedModel());
            empty.setScanned(0);
            return Result.succeed(empty, "知识库为空，请先上传文档");
        }

        List<double[]> queryVectors;
        try {
            queryVectors = embeddingClient.embed(embedding, Collections.singletonList(query.trim()));
        } catch (IllegalStateException e) {
            return Result.failed(null, e.getMessage());
        } catch (Exception e) {
            log.warn("[KbRag] 检索向量化失败: {}", e.getMessage());
            return Result.failed(null, "检索失败：" + e.getMessage());
        }
        double[] queryVector = queryVectors.get(0);

        Set<Long> lexicalHits = lexicalCandidates(uid, kbName, query.trim());
        RankDiagnostics diagnostics = new RankDiagnostics();
        List<KbSearchItem> fused = hybridRank(query.trim(), chunks, queryVector, lexicalHits,
                diagnostics, embedding.getEmbedModel());

        int limit = topK == null ? 5 : Math.max(1, Math.min(MAX_TOP_K, topK));
        fused = applyRerank(query.trim(), fused, limit);
        Map<Long, String> docNames = loadDocNames(uid);
        List<KbSearchItem> items = fused.size() > limit
                ? new ArrayList<KbSearchItem>(fused.subList(0, limit)) : fused;
        for (KbSearchItem item : items) {
            item.setDocName(docNames.get(item.getDocIid()));
        }

        KbSearchResult result = new KbSearchResult();
        result.setQuery(query.trim());
        result.setModel(embedding.getEmbedModel());
        result.setScanned(chunks.size());
        result.setSkipped(diagnostics.skipped());
        result.setItems(items);
        if (diagnostics.skipped() > 0) {
            log.warn("[KbRag] 向量路跳过 {} 块（模型不一致 {} / 维度不一致 {} / 解析失败 {}），当前模型 {}({} 维)",
                    diagnostics.skipped(), diagnostics.modelMismatch,
                    diagnostics.dimensionMismatch, diagnostics.unparsable,
                    embedding.getEmbedModel(), queryVector.length);
            result.setWarning("有 " + diagnostics.skipped()
                    + " 个分块的向量与当前向量模型 " + embedding.getEmbedModel()
                    + " 不匹配，已跳过语义比对，检索结果可能不完整；如更换过向量模型，请重新上传相关文档");
        }
        return Result.succeed(result, "检索成功");
    }

    /**
     * 二阶段精排：已配置 rerank 服务时，把混合检索的前若干候选取原文
     * 交给 rerank 模型重排，取回顺序覆盖融合序。
     *
     * <p>候选窗口取 limit*3（上限 24）：精排模型按对打分成本高，
     * 只对混合检索认为「可能有戏」的片段做，兼顾效果与延迟。</p>
     *
     * <p>任何失败（未配置 / 超时 / 响应异常）都降级返回原列表——
     * 精排是增强路径，绝不能阻塞检索主链路。</p>
     */
    private List<KbSearchItem> applyRerank(String query, List<KbSearchItem> fused, int limit) {
        try {
            RerankResolver.ResolvedRerank config = rerankResolver.resolve();
            if (config == null || fused.size() <= 1) {
                return fused;
            }
            int window = Math.min(Math.max(limit * 3, limit), 24);
            List<KbSearchItem> candidates = fused.size() > window
                    ? new ArrayList<KbSearchItem>(fused.subList(0, window)) : fused;
            List<String> docs = new ArrayList<String>();
            for (KbSearchItem item : candidates) {
                docs.add(item.getContent() == null ? "" : item.getContent());
            }
            List<Integer> order = rerankResolver.rerank(config, query, docs);
            if (order == null) {
                return fused;
            }
            List<KbSearchItem> reranked = new ArrayList<KbSearchItem>(candidates.size());
            for (Integer idx : order) {
                if (idx != null && idx >= 0 && idx < candidates.size()) {
                    reranked.add(candidates.get(idx));
                }
            }
            // 窗口外的尾部片段保持在后面，不因精排被整段丢弃
            for (KbSearchItem item : fused) {
                if (!reranked.contains(item)) {
                    reranked.add(item);
                }
            }
            log.info("[KbRag] rerank 生效: 候选 {} 条已重排", candidates.size());
            return reranked;
        } catch (Exception e) {
            log.warn("[KbRag] rerank 异常，降级混合排序: {}", e.getMessage());
            return fused;
        }
    }

    /**
     * 混合排序：向量路算余弦，关键词路按词项重叠排序，RRF 融合。
     *
     * <p>关键词路的召回结果作为入参传入而不是在这里查库——这样这段纯排序逻辑
     * 可以被单测直接驱动（见 KbRetrievalEvalTest），用来量化"混合检索比纯向量
     * 好多少"。I/O 与算法分离也让这个方法的职责更清楚。</p>
     *
     * @param query        用户问题（用于词项重叠打分）
     * @param chunks       候选分块（已按归属/知识库过滤）
     * @param queryVector  问题的向量
     * @param lexicalHits  关键词路召回的 chunk iid 集合；空集合表示该路无结果
     */
    public List<KbSearchItem> hybridRank(String query, List<KbChunkBean> chunks,
                                         double[] queryVector, Set<Long> lexicalHits) {
        return hybridRank(query, chunks, queryVector, lexicalHits, new RankDiagnostics(), null);
    }

    /**
     * 同 {@link #hybridRank(String, List, double[], Set)}，额外统计向量路被跳过的块。
     * 跳过原因对用户有意义（切换向量模型后旧文档会全部失效），不能只埋在日志里。
     */
    List<KbSearchItem> hybridRank(String query, List<KbChunkBean> chunks, double[] queryVector,
                                  Set<Long> lexicalHits, RankDiagnostics diagnostics) {
        return hybridRank(query, chunks, queryVector, lexicalHits, diagnostics, null);
    }

    /**
     * 带当前向量模型名的排序：模型名不同的分块直接跳过。
     *
     * <p>只看维度不够——换到维度相同的另一个模型时，旧向量仍会被当成有效候选，
     * 检索结果会悄悄变差。存量分块没有模型名（NULL），此时退回维度判断。</p>
     */
    List<KbSearchItem> hybridRank(String query, List<KbChunkBean> chunks, double[] queryVector,
                                  Set<Long> lexicalHits, RankDiagnostics diagnostics,
                                  String currentEmbeddingModel) {
        // ---- 向量路：对全部分块算余弦 ----
        Map<Long, KbSearchItem> byChunkIid = new HashMap<Long, KbSearchItem>();
        List<KbSearchItem> denseRanked = new ArrayList<KbSearchItem>();
        for (KbChunkBean chunk : chunks) {
            String chunkModel = chunk.getEmbeddingModel();
            if (currentEmbeddingModel != null && !currentEmbeddingModel.isEmpty()
                    && chunkModel != null && !chunkModel.isEmpty()
                    && !chunkModel.equals(currentEmbeddingModel)) {
                diagnostics.modelMismatch++;
                continue;
            }
            double[] vector = parseVector(chunk.getEmbedding());
            if (vector == null) {
                diagnostics.unparsable++;
                continue;
            }
            if (vector.length != queryVector.length) {
                diagnostics.dimensionMismatch++;
                continue;
            }
            KbSearchItem item = new KbSearchItem();
            item.setDocIid(chunk.getDocIid());
            item.setKbName(chunk.getKbName());
            item.setChunkIndex(chunk.getChunkIndex());
            item.setContent(chunk.getContent());
            item.setDenseScore(round4(cosine(queryVector, vector)));
            byChunkIid.put(chunk.getIid(), item);
            denseRanked.add(item);
        }
        // 余弦降序 = 向量路排名
        denseRanked.sort(new Comparator<KbSearchItem>() {
            @Override
            public int compare(KbSearchItem a, KbSearchItem b) {
                return Double.compare(b.getDenseScore(), a.getDenseScore());
            }
        });

        // ---- 关键词路：按词项重叠数排序 ----
        List<KbSearchItem> lexicalRanked = new ArrayList<KbSearchItem>();
        Set<Long> lexicalChunkIids = new HashSet<Long>();
        if (lexicalHits != null && !lexicalHits.isEmpty()) {
            for (KbChunkBean chunk : chunks) {
                if (!lexicalHits.contains(chunk.getIid())) {
                    continue;
                }
                KbSearchItem item = byChunkIid.get(chunk.getIid());
                if (item == null) {
                    continue;
                }
                item.setLexicalScore(countTermOverlap(query, chunk.getContent()));
                lexicalRanked.add(item);
                lexicalChunkIids.add(chunk.getIid());
            }
            lexicalRanked.sort(new Comparator<KbSearchItem>() {
                @Override
                public int compare(KbSearchItem a, KbSearchItem b) {
                    return Integer.compare(b.getLexicalScore(), a.getLexicalScore());
                }
            });
        }

        // ---- RRF 融合 ----
        // score = Σ 1/(k + rank)，k 取 60 是原论文与业界的常用值：
        // 大到能让头部条目的排名差占主导，又小到不完全忽略中下部。
        for (int i = 0; i < denseRanked.size(); i++) {
            denseRanked.get(i).setScore(round4(
                    denseRanked.get(i).getScore() + 1.0 / (RRF_K + i + 1)));
        }
        for (int i = 0; i < lexicalRanked.size(); i++) {
            lexicalRanked.get(i).setScore(round4(
                    lexicalRanked.get(i).getScore() + 1.0 / (RRF_K + i + 1)));
        }

        // 标注命中来源，便于解释「为什么这条排在前面」
        for (Map.Entry<Long, KbSearchItem> entry : byChunkIid.entrySet()) {
            KbSearchItem item = entry.getValue();
            boolean inDense = item.getDenseScore() >= 0;
            boolean inLexical = lexicalChunkIids.contains(entry.getKey());
            if (inDense && inLexical) {
                item.setChannel("both");
            } else if (inLexical) {
                item.setChannel("lexical");
            } else {
                item.setChannel("dense");
            }
        }

        List<KbSearchItem> fused = new ArrayList<KbSearchItem>(byChunkIid.values());
        fused.sort(new Comparator<KbSearchItem>() {
            @Override
            public int compare(KbSearchItem a, KbSearchItem b) {
                return Double.compare(b.getScore(), a.getScore());
            }
        });
        return fused;
    }

    /**
     * 关键词路召回：用 PostgreSQL 全文检索找出内容命中的分块 iid。
     *
     * <p>用 {@code plainto_tsquery('simple', ...)} 而不是 {@code to_tsquery}：
     * 前者把用户输入当纯文本处理，不解析 {@code & | !} 等操作符，
     * 避免了「用户输入包含布尔语法导致查询报错」，也缩小了注入面。</p>
     *
     * <p>查询文本仍必须转义——它来自用户输入。这里只做最小必要转义
     * （单引号加倍 + 去 NUL），因为 plainto_tsquery 内部不再解释语法。</p>
     *
     * <p>任何异常都返回空集合而非抛出：让检索降级为纯向量路。</p>
     */
    private Set<Long> lexicalCandidates(String uid, String kbName, String query) {
        Set<Long> hits = new HashSet<Long>();
        try {
            KbChunkBean where = new KbChunkBean();
            where.setLastuser(uid);
            if (kbName != null && !kbName.trim().isEmpty()) {
                where.setKbName(kbName.trim());
            }
            String escaped = escapeSqlLiteral(query);
            String ftsWhere = "to_tsvector('simple', \"content\") @@ plainto_tsquery('simple', '" + escaped + "')";
            List<KbChunkBean> matched = kbChunkBeanMapper.select(null, where, ftsWhere);
            if (matched != null) {
                for (KbChunkBean chunk : matched) {
                    if (chunk.getIid() != null) {
                        hits.add(chunk.getIid());
                    }
                }
            }
        } catch (Exception e) {
            // 索引未建、SQL 方言差异、权限变更等都可能让这一路失败。
            // 检索是增强而非依赖：降级为纯向量路，只在日志里留痕。
            log.warn("[KbRag] 关键词路召回失败，降级为纯向量检索: {}", e.getMessage());
        }
        return hits;
    }

    /**
     * 词项重叠数：查询里有多少个词项出现在分块正文中。
     *
     * <p>用作关键词路的排序依据。intentionally 简单——
     * RRF 只需要排名，不需要精确的 BM25 分值；而且这一路已有 PG 全文检索
     * 做过一轮筛选，这里只是把召回的候选排出先后。</p>
     */
    static int countTermOverlap(String query, String content) {
        if (query == null || content == null) {
            return 0;
        }
        String lowerContent = content.toLowerCase();
        int overlap = 0;
        for (String term : tokenize(query)) {
            if (lowerContent.contains(term)) {
                overlap++;
            }
        }
        return overlap;
    }

    /**
     * 中英混合取词项。
     *
     * <p>英文按单词取（{@code approval} 是一个词项）；中文不按单字取——
     * 单字噪声太大（「的」「是」到处都有，会把所有片段都算成命中），
     * 也不引入外部分词器（项目不该为这点事多一个依赖）。
     * 采用折衷的<b>二元滑窗</b>：连续中文段每两字取一个词项，
     * 「报销流程」→ {@code 报销、销流、流程}。这覆盖了绝大多数
     * 中文词组（中文词以二字居多），且对单字查询仍能通过包含判断命中。</p>
     */
    static List<String> tokenize(String text) {
        List<String> terms = new ArrayList<String>();
        StringBuilder cjkRun = new StringBuilder();
        StringBuilder latin = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                if (latin.length() > 0) {
                    terms.add(latin.toString());
                    latin.setLength(0);
                }
                cjkRun.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                if (cjkRun.length() > 0) {
                    addCjkBigrams(cjkRun, terms);
                    cjkRun.setLength(0);
                }
                latin.append(c);
            }
            // 其它字符（标点、空格）作为分隔符，直接跳过
        }
        if (cjkRun.length() > 0) {
            addCjkBigrams(cjkRun, terms);
        }
        if (latin.length() > 0) {
            terms.add(latin.toString());
        }
        return terms;
    }

    /** 连续中文段转二元词项；只剩单字时保留单字（好过丢掉） */
    private static void addCjkBigrams(StringBuilder run, List<String> terms) {
        String s = run.toString();
        if (s.length() == 1) {
            terms.add(s);
            return;
        }
        for (int i = 0; i + 1 < s.length(); i++) {
            terms.add(s.substring(i, i + 2));
        }
    }

    /** SQL 字面量转义：先去 NUL，再转义反斜杠、最后单引号加倍。
     *  顺序不能反——必须先处理反斜杠，否则转义后的单引号可被 \' 绕过
     *  （在 standard_conforming_strings=off 的旧配置下即为注入漏洞）。 */
    static String escapeSqlLiteral(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\0", "").replace("\\", "\\\\").replace("'", "''");
    }

    private Long findDocIid(String kbName, String name, String uid) {
        PageRequest pageRequest = new PageRequest(1, 1);
        pageRequest.setOrder("iid");
        pageRequest.setDesc("desc");
        KbDocBean where = new KbDocBean();
        where.setKbName(kbName);
        where.setName(name);
        where.setLastuser(uid);
        Result<PageResult<KbDocBean>> result = kbDocBeanMapper.selectPage(pageRequest, where);
        if (result == null || result.getDatas() == null || result.getDatas().getContent() == null
                || result.getDatas().getContent().isEmpty()) {
            return null;
        }
        return result.getDatas().getContent().get(0).getIid();
    }

    /**
     * 载入文档名映射，仅限当前用户的文档。
     *
     * <p>检索结果里的 docName 用于展示「来源：文档名」，因此这里同样按
     * lastuser 收窄，既避免全表扫描，也保证不会把他人文档名带进响应。</p>
     */
    private Map<Long, String> loadDocNames(String uid) {
        Map<Long, String> names = new HashMap<Long, String>();
        KbDocBean where = new KbDocBean();
        where.setLastuser(uid);
        List<KbDocBean> docs = kbDocBeanMapper.select(null, where);
        if (docs != null) {
            for (KbDocBean doc : docs) {
                names.put(doc.getIid(), doc.getName());
            }
        }
        return names;
    }

    /**
     * 分块：优先按空行分段落，段落打包进不超过 CHUNK_SIZE 的块；
     * 超长段落按长度滑动窗口（带重叠）再切。
     */
    static List<String> chunkText(String text) {
        List<String> chunks = new ArrayList<String>();
        String normalized = text.replace("\r\n", "\n").trim();
        if (normalized.isEmpty()) {
            return chunks;
        }
        StringBuilder current = new StringBuilder();
        for (String paragraph : normalized.split("\n\\s*\n")) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > CHUNK_SIZE) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                chunks.addAll(splitLongParagraph(trimmed));
                continue;
            }
            if (current.length() > 0 && current.length() + trimmed.length() + 2 > CHUNK_SIZE) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(trimmed);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    private static List<String> splitLongParagraph(String paragraph) {
        List<String> pieces = new ArrayList<String>();
        int start = 0;
        while (start < paragraph.length()) {
            int end = Math.min(paragraph.length(), start + CHUNK_SIZE);
            pieces.add(paragraph.substring(start, end));
            if (end >= paragraph.length()) {
                break;
            }
            start = end - CHUNK_OVERLAP;
        }
        return pieces;
    }

    /** 混合排序的降级统计，用于把「向量路被跳过」的原因暴露给调用方 */
    static class RankDiagnostics {
        int modelMismatch;
        int dimensionMismatch;
        int unparsable;

        int skipped() {
            return modelMismatch + dimensionMismatch + unparsable;
        }
    }

    private double[] parseVector(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, double[].class);
        } catch (Exception e) {
            return null;
        }
    }

    private static double cosine(double[] a, double[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static String toJson(double[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.6f", vector[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
