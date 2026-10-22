package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.KbChunkBean;
import com.aiproject.ai.service.bean.KbDocBean;
import com.aiproject.ai.service.gateway.EmbeddingClient;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 本地知识库（RAG）：文档解析 → 分块 → 向量化 → 相似度检索。
 * <p>
 * 向量以 JSON 文本存 kbchunkbean.embedding，检索时在内存中做余弦相似度排序；
 * 当前实现面向中小规模文档库（万块以内），后续可平滑迁移 pgvector。
 */
@Slf4j
@Service
public class KbRagHolder {

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

    /** 文档列表（含分块数与状态），不返回任何向量与正文 */
    public Result<List<KbDocBean>> listDocs(String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(null, "未登录或登录已过期");
        }
        List<KbDocBean> docs = kbDocBeanMapper.select(null, new KbDocBean());
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
            return Result.failed(null, "保存文档失败：无法定位新文档");
        }

        for (int i = 0; i < chunks.size(); i++) {
            KbChunkBean chunk = new KbChunkBean();
            chunk.setDocIid(docIid);
            chunk.setKbName(kbName);
            chunk.setChunkIndex(i);
            chunk.setContent(chunks.get(i));
            chunk.setEmbedding(toJson(vectors.get(i)));
            chunk.setTokenCount(chunks.get(i).length());
            chunk.setLastuser(uid);
            if (kbChunkBeanMapper.install(chunk) <= 0) {
                log.warn("[KbRag] 分块写入失败, docIid={}, index={}", docIid, i);
            }
        }
        return Result.succeed(doc, "文档已入库，共 " + chunks.size() + " 块");
    }

    public Result<Boolean> deleteDoc(String uid, KbDocBean data) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(false, "未登录或登录已过期");
        }
        if (data == null || data.getIid() == null) {
            return Result.failed(false, "文档 iid 不能为空");
        }
        KbDocBean where = new KbDocBean();
        where.setIid(data.getIid());
        if (kbDocBeanMapper.delete(where) <= 0) {
            return Result.failed(false, "删除失败：文档不存在");
        }
        KbChunkBean chunkWhere = new KbChunkBean();
        chunkWhere.setDocIid(data.getIid());
        kbChunkBeanMapper.delete(chunkWhere);
        return Result.succeed(true, "删除成功");
    }

    /**
     * 相似度检索：查询向量化后与知识库分块做余弦匹配，返回 topK。
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

        KbChunkBean where = new KbChunkBean();
        if (kbName != null && !kbName.trim().isEmpty()) {
            where.setKbName(kbName.trim());
        }
        List<KbChunkBean> chunks = kbChunkBeanMapper.select(null, where);
        if (chunks == null || chunks.isEmpty()) {
            KbSearchResult empty = new KbSearchResult();
            empty.setQuery(query.trim());
            empty.setModel(embedding.getEmbedModel());
            empty.setScanned(0);
            return Result.succeed(empty, "知识库为空，请先上传文档");
        }

        List<KbSearchItem> scored = new ArrayList<KbSearchItem>(chunks.size());
        for (KbChunkBean chunk : chunks) {
            double[] vector = parseVector(chunk.getEmbedding());
            if (vector == null || vector.length != queryVector.length) {
                continue;
            }
            KbSearchItem item = new KbSearchItem();
            item.setDocIid(chunk.getDocIid());
            item.setKbName(chunk.getKbName());
            item.setChunkIndex(chunk.getChunkIndex());
            item.setContent(chunk.getContent());
            item.setScore(round4(cosine(queryVector, vector)));
            scored.add(item);
        }
        scored.sort(new Comparator<KbSearchItem>() {
            @Override
            public int compare(KbSearchItem a, KbSearchItem b) {
                return Double.compare(b.getScore(), a.getScore());
            }
        });

        int limit = topK == null ? 5 : Math.max(1, Math.min(MAX_TOP_K, topK));
        Map<Long, String> docNames = loadDocNames();
        List<KbSearchItem> items = scored.size() > limit ? new ArrayList<KbSearchItem>(scored.subList(0, limit)) : scored;
        for (KbSearchItem item : items) {
            item.setDocName(docNames.get(item.getDocIid()));
        }

        KbSearchResult result = new KbSearchResult();
        result.setQuery(query.trim());
        result.setModel(embedding.getEmbedModel());
        result.setScanned(chunks.size());
        result.setItems(items);
        return Result.succeed(result, "检索成功");
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

    private Map<Long, String> loadDocNames() {
        Map<Long, String> names = new HashMap<Long, String>();
        List<KbDocBean> docs = kbDocBeanMapper.select(null, new KbDocBean());
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
