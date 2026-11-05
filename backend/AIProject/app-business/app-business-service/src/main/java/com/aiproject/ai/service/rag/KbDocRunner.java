package com.aiproject.ai.service.rag;

import com.aiproject.ai.service.bean.KbChunkBean;
import com.aiproject.ai.service.bean.KbDocBean;
import com.aiproject.ai.service.bean.KbDocContentBean;
import com.aiproject.ai.service.gateway.EmbeddingClient;
import com.aiproject.ai.service.holde.KbRagHolder;
import com.aiproject.ai.service.mapper.KbChunkBeanMapper;
import com.aiproject.ai.service.mapper.KbDocBeanMapper;
import com.aiproject.ai.service.mapper.KbDocContentBeanMapper;
import com.heneng.hnsp.common.db.utils.PageRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 文档处理执行器：分块 → 分批向量化 → 落库，状态与进度实时写回。
 *
 * <p>为什么向量全部算完才落分块：失败时不会留下「半篇可检索、半篇缺失」的文档。
 * 500 块 × 1024 维约 4MB，单机并发 2 个任务的内存代价可控；换来的是重试语义干净——
 * 失败要么没有分块，要么整篇替换。</p>
 *
 * <p>分批而不是一次请求全部：大文档一次性发给向量服务容易触发请求体上限或超时，
 * 失败后整篇重来；分批后只重试当前批，并且进度可以按批推进。</p>
 */
@Slf4j
@Service
public class KbDocRunner {

    /** 超过 30 分钟仍未结束的任务视为僵死（进程重启、线程异常退出等） */
    private static final long STALE_DOC_MS = 30 * 60 * 1000L;
    private static final int EMBED_BATCH_SIZE = 32;
    private static final int BATCH_ATTEMPTS = 2;
    private static final long BATCH_RETRY_DELAY_MS = 200L;
    private static final int TRUNCATE_ERROR_CHARS = 1000;

    @Autowired
    KbDocBeanMapper<KbDocBean> docMapper;

    @Autowired
    KbDocContentBeanMapper<KbDocContentBean> contentMapper;

    @Autowired
    KbChunkBeanMapper<KbChunkBean> chunkMapper;

    @Autowired
    EmbeddingClient embeddingClient;

    /** 后台执行入口：任何未捕获异常都必须落成 failed，不能让文档永久停在 processing */
    public void execute(Long docIid, String uid) {
        try {
            doExecute(docIid, uid);
        } catch (Exception e) {
            log.error("[KbDoc] 文档处理异常, docIid={}, uid={}: {}", docIid, uid, e.getMessage(), e);
            fail(uid, docIid, "处理异常：" + truncate(e.getMessage()));
        }
    }

    private void doExecute(Long docIid, String uid) {
        KbDocBean doc = findDoc(uid, docIid);
        if (doc == null || !KbDocStatus.QUEUED.equals(doc.getStatus())) {
            // 已删除 / 已被重试任务接手 / 重复提交：不再执行
            return;
        }
        KbDocBean started = new KbDocBean();
        started.setStatus(KbDocStatus.CHUNKING);
        started.setProgress(10);
        started.setStartedAt(System.currentTimeMillis());
        if (!transition(uid, docIid, KbDocStatus.QUEUED, started)) {
            log.warn("[KbDoc] 文档已不存在或状态已变化，跳过执行, docIid={}", docIid);
            return;
        }

        KbDocContentBean source = findContent(uid, docIid);
        if (source == null || source.getContent() == null || source.getContent().trim().isEmpty()) {
            fail(uid, docIid, "找不到原始正文，请删除后重新上传");
            return;
        }
        List<String> chunks = KbRagHolder.chunkText(source.getContent());
        if (chunks.isEmpty()) {
            fail(uid, docIid, "文档解析后没有有效内容");
            return;
        }
        if (chunks.size() > KbRagHolder.MAX_CHUNKS) {
            fail(uid, docIid, "文档分块过多（" + chunks.size()
                    + " 块），单篇最多 " + KbRagHolder.MAX_CHUNKS + " 块");
            return;
        }

        EmbeddingClient.ResolvedEmbedding embedding = embeddingClient.resolve();
        if (embedding == null) {
            fail(uid, docIid, "尚未配置向量模型：请在模型配置中启用 OpenAI 直连并填写向量模型（embedModel）");
            return;
        }

        KbDocBean chunked = new KbDocBean();
        chunked.setStatus(KbDocStatus.EMBEDDING);
        chunked.setProgress(20);
        chunked.setChunkCount(chunks.size());
        chunked.setCharCount((long) source.getContent().length());
        if (!transition(uid, docIid, KbDocStatus.CHUNKING, chunked)) {
            return;
        }

        List<double[]> vectors = new ArrayList<double[]>(chunks.size());
        int totalBatches = (chunks.size() + EMBED_BATCH_SIZE - 1) / EMBED_BATCH_SIZE;
        for (int batch = 0; batch < totalBatches; batch++) {
            int from = batch * EMBED_BATCH_SIZE;
            int to = Math.min(chunks.size(), from + EMBED_BATCH_SIZE);
            List<double[]> batchVectors;
            try {
                batchVectors = embedWithRetry(embedding, chunks.subList(from, to), batch + 1, totalBatches);
            } catch (Exception e) {
                fail(uid, docIid, truncate(e.getMessage()));
                return;
            }
            if (batchVectors == null || batchVectors.size() != to - from) {
                fail(uid, docIid, "向量化返回数量不一致（第 " + (batch + 1) + "/" + totalBatches + " 批）");
                return;
            }
            for (double[] vector : batchVectors) {
                if (vector == null || vector.length == 0) {
                    fail(uid, docIid, "向量化返回了空向量（第 " + (batch + 1) + "/" + totalBatches + " 批）");
                    return;
                }
                if (!vectors.isEmpty() && vectors.get(0).length != vector.length) {
                    fail(uid, docIid, "同一文档的向量维度不一致，请检查向量模型配置");
                    return;
                }
                vectors.add(vector);
            }

            int progress = 20 + (int) Math.floor(70.0 * vectors.size() / chunks.size());
            KbDocBean progressUpdate = new KbDocBean();
            progressUpdate.setProgress(Math.min(90, progress));
            if (!transition(uid, docIid, KbDocStatus.EMBEDDING, progressUpdate)) {
                // 用户在处理过程中删除了文档：不再继续消耗向量额度
                log.warn("[KbDoc] 文档在向量化过程中被删除，终止执行, docIid={}", docIid);
                return;
            }
        }

        try {
            // 重试时清掉上一次可能残留的分块；正常首次上传没有分块，删除是空操作
            deleteChunks(uid, docIid);
            for (int i = 0; i < chunks.size(); i++) {
                KbChunkBean chunk = new KbChunkBean();
                chunk.setDocIid(docIid);
                chunk.setKbName(doc.getKbName());
                chunk.setChunkIndex(i);
                chunk.setContent(chunks.get(i));
                chunk.setEmbedding(toJson(vectors.get(i)));
                chunk.setEmbeddingModel(embedding.getEmbedModel());
                chunk.setEmbeddingDim(vectors.get(i).length);
                chunk.setTokenCount(chunks.get(i).length());
                chunk.setLastuser(uid);
                if (chunkMapper.install(chunk) <= 0) {
                    throw new IllegalStateException("第 " + (i + 1) + " 个分块写入失败");
                }
            }
        } catch (Exception e) {
            log.error("[KbDoc] 分块落库失败, docIid={}: {}", docIid, e.getMessage(), e);
            fail(uid, docIid, "分块保存失败，已回滚：" + truncate(e.getMessage()));
            return;
        }

        KbDocBean ready = new KbDocBean();
        ready.setStatus(KbDocStatus.READY);
        ready.setProgress(100);
        ready.setChunkCount(chunks.size());
        ready.setCharCount((long) source.getContent().length());
        ready.setEmbeddingModel(embedding.getEmbedModel());
        ready.setFinishedAt(System.currentTimeMillis());
        if (!transition(uid, docIid, KbDocStatus.EMBEDDING, ready)) {
            // 所有分块写完后文档被删除：把刚落库的分块清掉，避免检索命中已删除文档
            log.warn("[KbDoc] 文档在落库完成时已被删除，清理分块, docIid={}", docIid);
            deleteChunks(uid, docIid);
        }
    }

    private List<double[]> embedWithRetry(EmbeddingClient.ResolvedEmbedding embedding,
                                          List<String> batch, int batchNo, int totalBatches) {
        Exception last = null;
        for (int attempt = 1; attempt <= BATCH_ATTEMPTS; attempt++) {
            try {
                return embeddingClient.embed(embedding, batch);
            } catch (Exception e) {
                last = e;
                if (attempt < BATCH_ATTEMPTS) {
                    try {
                        Thread.sleep(BATCH_RETRY_DELAY_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw new IllegalStateException("向量化失败（第 " + batchNo + "/" + totalBatches
                + " 批）：" + (last == null ? "未知错误" : truncate(last.getMessage())), last);
    }

    /**
     * 回收僵死任务：进程重启或线程异常退出后，文档会停在 queued/chunking/embedding。
     * 不回收会让用户永远看到「处理中」，且无法重新上传（去重索引还占着）。
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 2 * 60 * 1000)
    public void recoverStaleDocs() {
        long cutoff = System.currentTimeMillis() - STALE_DOC_MS;
        int recovered = 0;
        for (String status : new String[]{
                KbDocStatus.QUEUED, KbDocStatus.CHUNKING, KbDocStatus.EMBEDDING}) {
            KbDocBean where = new KbDocBean();
            where.setStatus(status);
            PageRequest page = new PageRequest(1, 500);
            page.setOrder("iid");
            page.setDesc("asc");
            List<KbDocBean> docs;
            try {
                docs = docMapper.select(page, where);
            } catch (Exception e) {
                log.warn("[KbDoc] 查询僵死任务失败, status={}: {}", status, e.getMessage());
                continue;
            }
            if (docs == null) {
                continue;
            }
            for (KbDocBean doc : docs) {
                if (doc.getStartedAt() == null || doc.getStartedAt() >= cutoff) {
                    continue;
                }
                try {
                    // 先 CAS 把活动状态改为 failed：如果 Runner 刚好完成并置 ready，
                    // 这里会更新 0 行，绝不能再去删它刚落库的分块
                    KbDocBean update = new KbDocBean();
                    update.setStatus(KbDocStatus.FAILED);
                    update.setErrorMsg("处理超时（超过 30 分钟未完成），请重试");
                    update.setFinishedAt(System.currentTimeMillis());
                    KbDocBean casWhere = docWhere(doc.getLastuser(), doc.getIid());
                    casWhere.setStatus(status);
                    if (docMapper.update(update, casWhere) <= 0) {
                        continue;
                    }
                    // CAS 成功后再清残片；Runner 后续的状态更新会因状态已变而停止
                    deleteChunks(doc.getLastuser(), doc.getIid());
                    recovered++;
                } catch (Exception e) {
                    log.warn("[KbDoc] 回收僵死任务失败, docIid={}: {}", doc.getIid(), e.getMessage());
                }
            }
        }
        if (recovered > 0) {
            log.warn("[KbDoc] 已回收 {} 个僵死文档处理任务", recovered);
        }
    }

    private KbDocBean findDoc(String uid, Long docIid) {
        if (docIid == null || docIid <= 0) {
            return null;
        }
        try {
            List<KbDocBean> docs = docMapper.select(null, docWhere(uid, docIid));
            return docs == null || docs.isEmpty() ? null : docs.get(0);
        } catch (Exception e) {
            log.warn("[KbDoc] 查询文档失败, docIid={}: {}", docIid, e.getMessage());
            return null;
        }
    }

    private KbDocContentBean findContent(String uid, Long docIid) {
        KbDocContentBean where = new KbDocContentBean();
        where.setDocIid(docIid);
        where.setLastuser(uid);
        try {
            List<KbDocContentBean> list = contentMapper.select(null, where);
            return list == null || list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            log.warn("[KbDoc] 读取原始正文失败, docIid={}: {}", docIid, e.getMessage());
            return null;
        }
    }

    private boolean transition(String uid, Long docIid, String expectedStatus, KbDocBean update) {
        KbDocBean where = docWhere(uid, docIid);
        where.setStatus(expectedStatus);
        try {
            return docMapper.update(update, where) > 0;
        } catch (Exception e) {
            log.error("[KbDoc] 更新文档状态失败, docIid={}, {} -> {}: {}", docIid,
                    expectedStatus, update.getStatus(), e.getMessage());
            return false;
        }
    }

    private void fail(String uid, Long docIid, String message) {
        deleteChunks(uid, docIid);
        KbDocBean update = new KbDocBean();
        update.setStatus(KbDocStatus.FAILED);
        update.setErrorMsg(truncate(message));
        update.setFinishedAt(System.currentTimeMillis());
        try {
            docMapper.update(update, docWhere(uid, docIid));
        } catch (Exception e) {
            log.error("[KbDoc] 标记文档失败时出错, docIid={}: {}", docIid, e.getMessage(), e);
        }
    }

    private void deleteChunks(String uid, Long docIid) {
        if (docIid == null) {
            return;
        }
        KbChunkBean where = new KbChunkBean();
        where.setDocIid(docIid);
        where.setLastuser(uid);
        try {
            chunkMapper.delete(where);
        } catch (Exception e) {
            log.warn("[KbDoc] 清理分块失败, docIid={}: {}", docIid, e.getMessage());
        }
    }

    private static KbDocBean docWhere(String uid, Long docIid) {
        KbDocBean where = new KbDocBean();
        where.setIid(docIid);
        where.setLastuser(uid);
        return where;
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

    private static String truncate(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "未知错误";
        }
        String singleLine = value.replaceAll("\\s+", " ").trim();
        return singleLine.length() > TRUNCATE_ERROR_CHARS
                ? singleLine.substring(0, TRUNCATE_ERROR_CHARS) + "…" : singleLine;
    }
}
