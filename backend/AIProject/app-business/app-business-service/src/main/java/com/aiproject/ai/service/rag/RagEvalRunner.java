package com.aiproject.ai.service.rag;

import com.aiproject.ai.service.bean.RagEvalCaseBean;
import com.aiproject.ai.service.bean.RagEvalCaseResultBean;
import com.aiproject.ai.service.bean.NotificationBean;
import com.aiproject.ai.service.bean.RagEvalDatasetBean;
import com.aiproject.ai.service.bean.RagEvalRunBean;
import com.aiproject.ai.service.gateway.dto.KbSearchItem;
import com.aiproject.ai.service.gateway.dto.KbSearchResult;
import com.aiproject.ai.service.holde.KbRagHolder;
import com.aiproject.ai.service.holde.NotificationHolder;
import com.aiproject.ai.service.notification.NotificationTypes;
import com.aiproject.ai.service.mapper.RagEvalCaseBeanMapper;
import com.aiproject.ai.service.mapper.RagEvalCaseResultBeanMapper;
import com.aiproject.ai.service.mapper.RagEvalDatasetBeanMapper;
import com.aiproject.ai.service.mapper.RagEvalRunBeanMapper;
import com.aiproject.ai.service.rag.dto.RagEvalHitDto;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 评估运行器：逐题调用真实检索，用 {@link RagEvaluator} 计算指标，再落库。
 *
 * <p>为什么是异步：每道题都要调用一次向量服务，55 道题在真实网络下通常超过
 * 前端 30 秒请求超时。接口只负责创建运行记录并提交任务，前端凭 runKey 轮询
 * {@code processed/total}，进度是真实处理数而不是假动画。</p>
 *
 * <p>为什么每个用例独立 try/catch：单题检索失败（向量服务抖动、超时）不应该
 * 让整次评估没有结果。失败题按 0 分计入指标并在逐题明细中标记，运行状态变为
 * {@code partial}；只有全部题目都失败才是 {@code failed}。</p>
 */
@Slf4j
@Service
public class RagEvalRunner {

    /** 超过 30 分钟仍未结束的运行视为僵死（服务重启、线程池异常等） */
    private static final long STALE_RUN_MS = 30 * 60 * 1000L;
    private static final int TRUNCATE_ERROR_CHARS = 1000;
    /**
     * 指标口径版本：Recall/Precision 按文档去重、NDCG 二值相关、关键词要求同块全部命中。
     * 口径一旦变化必须升版本，否则新旧运行的「对比基线」是在比较两套算法。
     */
    private static final String METRIC_SPEC = "rag-eval-v1";
    private static final String RETRIEVAL_HYBRID = "hybrid-rrf";
    private static final String RETRIEVAL_HYBRID_RERANK = "hybrid-rrf+rerank";
    private static final int PREVIEW_CHARS = 200;

    @Autowired
    RagEvalRunBeanMapper<RagEvalRunBean> runMapper;

    @Autowired
    RagEvalCaseResultBeanMapper<RagEvalCaseResultBean> resultMapper;

    @Autowired
    RagEvalCaseBeanMapper<RagEvalCaseBean> caseMapper;

    @Autowired
    RagEvalDatasetBeanMapper<RagEvalDatasetBean> datasetMapper;

    @Autowired
    KbRagHolder kbRagHolder;

    @Autowired
    NotificationHolder notificationHolder;

    /**
     * 后台执行入口。任何未捕获异常都要落到运行记录上，否则前端会一直看到
     * running、并且活动运行唯一索引会挡住该数据集后续的所有评估。
     */
    public void execute(String runKey, String uid) {
        try {
            doExecute(runKey, uid);
        } catch (Exception e) {
            log.error("[RagEval] 评估执行异常, runKey={}: {}", runKey, e.getMessage(), e);
            failRun(runKey, uid, "评估执行异常：" + truncate(e.getMessage()));
        }
    }

    private void doExecute(String runKey, String uid) {
        RagEvalRunBean run = findRun(uid, runKey);
        if (run == null || !RagEvalStatus.QUEUED.equals(run.getStatus())) {
            // 已被恢复任务标记失败 / 被删除 / 重复提交：不再执行
            return;
        }
        long startedAt = System.currentTimeMillis();
        RagEvalRunBean started = new RagEvalRunBean();
        started.setStatus(RagEvalStatus.RUNNING);
        started.setStartedAt(startedAt);
        started.setProcessed(0);
        if (!updateRun(runKey, uid, started)) {
            log.warn("[RagEval] 运行记录已不存在，跳过执行, runKey={}", runKey);
            return;
        }

        RagEvalDatasetBean dataset = findDataset(uid, run.getDatasetKey());
        if (dataset == null) {
            failRun(runKey, uid, "数据集已被删除");
            return;
        }
        List<RagEvalCaseBean> cases = loadCases(uid, dataset);
        if (cases.isEmpty()) {
            failRun(runKey, uid, "数据集中没有可用用例");
            return;
        }
        int topK = run.getTopK() == null ? 5 : run.getTopK();
        String rerankMode = RagEvalRerankMode.normalize(run.getRerankMode());

        // 运行开始时快照数据集信息：编辑数据集不应改写正在跑的这一轮
        RagEvalRunBean snapshot = new RagEvalRunBean();
        snapshot.setDatasetName(dataset.getName());
        snapshot.setKbName(dataset.getKbName());
        snapshot.setTotal(cases.size());
        updateRun(runKey, uid, snapshot);

        List<RunCaseData> evaluated = new ArrayList<RunCaseData>(cases.size());
        for (int i = 0; i < cases.size(); i++) {
            RunCaseData data = evaluateCase(runKey, uid, cases.get(i), dataset.getKbName(),
                    topK, rerankMode, i);
            evaluated.add(data);
            RagEvalRunBean progress = new RagEvalRunBean();
            progress.setProcessed(i + 1);
            if (!updateRun(runKey, uid, progress)) {
                // 运行记录被删除（用户删除或数据集级联删除）：立即停止，避免继续消耗额度
                log.warn("[RagEval] 运行过程中记录被删除，终止执行, runKey={}", runKey);
                return;
            }
        }

        Map<String, Long> docIds = new HashMap<String, Long>();
        AtomicLong nextDocId = new AtomicLong(1L);
        List<RagEvaluator.EvalCase> evalCases = new ArrayList<RagEvaluator.EvalCase>(evaluated.size());
        for (RunCaseData data : evaluated) {
            evalCases.add(toEvalCase(data, docIds, nextDocId));
        }
        RagEvaluator.EvalReport report = RagEvaluator.evaluate(evalCases, topK);

        int persistFailures = 0;
        for (int i = 0; i < evaluated.size(); i++) {
            RunCaseData data = evaluated.get(i);
            RagEvaluator.CaseResult caseResult = report.getCases().get(i);
            try {
                if (resultMapper.install(toResultBean(uid, runKey, data, caseResult)) <= 0) {
                    persistFailures++;
                }
            } catch (Exception e) {
                persistFailures++;
                log.warn("[RagEval] 逐题结果保存失败, runKey={}, index={}: {}",
                        runKey, data.index, e.getMessage());
            }
        }

        int keywordEvaluated = 0;
        int keywordHits = 0;
        int errors = 0;
        boolean rerankApplied = false;
        String firstError = null;
        String model = null;
        for (RunCaseData data : evaluated) {
            if (data.keywordEvaluated) {
                keywordEvaluated++;
                if (data.keywordHit) {
                    keywordHits++;
                }
            }
            if (data.error != null) {
                errors++;
                if (firstError == null) {
                    firstError = data.error;
                }
            }
            if (model == null && data.model != null && !data.model.trim().isEmpty()) {
                model = data.model;
            }
            if (data.rerankApplied) {
                rerankApplied = true;
            }
        }

        String status;
        String errorMessage = null;
        if (errors == evaluated.size()) {
            status = RagEvalStatus.FAILED;
            errorMessage = "全部用例检索失败：" + (firstError == null ? "未知错误" : firstError);
        } else if (errors > 0) {
            status = RagEvalStatus.PARTIAL;
            errorMessage = "部分用例检索失败（" + errors + "/" + evaluated.size() + "）：" + firstError;
        } else if (persistFailures > 0) {
            status = RagEvalStatus.PARTIAL;
            errorMessage = persistFailures + " 条逐题结果保存失败，其余结果可用";
        } else {
            status = RagEvalStatus.SUCCESS;
        }
        if (persistFailures > 0 && errors > 0) {
            errorMessage = errorMessage + "；" + persistFailures + " 条逐题结果保存失败";
        }

        long finishedAt = System.currentTimeMillis();
        RagEvalRunBean finished = new RagEvalRunBean();
        finished.setStatus(status);
        finished.setProcessed(evaluated.size());
        finished.setTotal(evaluated.size());
        finished.setRecall(report.getRecallAtK());
        finished.setPrecision(report.getPrecisionAtK());
        finished.setMrr(report.getMrr());
        finished.setNdcg(report.getNdcgAtK());
        finished.setKeywordHitRate(keywordEvaluated == 0 ? 0.0 : (double) keywordHits / keywordEvaluated);
        finished.setKeywordEvaluatedCount(keywordEvaluated);
        finished.setMissCount(report.getMisses().size());
        finished.setEmbeddingModel(model);
        finished.setMetricSpec(METRIC_SPEC);
        finished.setRetrievalMode(rerankApplied ? RETRIEVAL_HYBRID_RERANK : RETRIEVAL_HYBRID);
        finished.setRerankApplied(rerankApplied);
        finished.setErrorMessage(truncate(errorMessage));
        finished.setFinishedAt(finishedAt);
        finished.setDurationMs(finishedAt - startedAt);
        if (!updateRun(runKey, uid, finished)) {
            log.error("[RagEval] 评估结果落库失败，运行记录可能已被删除, runKey={}", runKey);
            return;
        }
        notifyFinished(uid, run, dataset, finished);
    }

    /**
     * 评估结束后给发起人留一条通知：用户可能早已离开评估页，
     * 铃铛是唯一能主动告诉他「跑完了 / 失败了」的渠道。
     *
     * <p>同一 runKey 的终态事件共用 eventKey，正常收尾与僵死回收
     * 即使并发也不会给用户发两条结论。</p>
     */
    private void notifyFinished(String uid, RagEvalRunBean run, RagEvalDatasetBean dataset,
                                RagEvalRunBean finished) {
        String datasetName = dataset != null && dataset.getName() != null
                ? dataset.getName() : "评估数据集";
        String status = finished.getStatus();
        String level;
        String title;
        String content;
        if (RagEvalStatus.SUCCESS.equals(status)) {
            level = NotificationTypes.LEVEL_SUCCESS;
            title = "RAG 评估完成";
            content = "数据集《" + datasetName + "》共 " + finished.getTotal() + " 题：Recall@k "
                    + percent(finished.getRecall()) + "，MRR " + decimal(finished.getMrr())
                    + "，耗时 " + seconds(finished.getDurationMs()) + " 秒。";
        } else if (RagEvalStatus.PARTIAL.equals(status)) {
            level = NotificationTypes.LEVEL_WARNING;
            title = "RAG 评估部分完成";
            content = "数据集《" + datasetName + "》有部分用例失败：" + finished.getErrorMessage();
        } else {
            level = NotificationTypes.LEVEL_ERROR;
            title = "RAG 评估失败";
            content = "数据集《" + datasetName + "》评估失败："
                    + (finished.getErrorMessage() == null ? "全部用例检索失败" : finished.getErrorMessage());
        }
        notificationHolder.push(uid, notification("rag-eval:" + run.getRunKey() + ":terminal",
                level, title, content, ragEvalLink(run.getDatasetKey())));
    }

    private static String percent(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private static String decimal(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.3f", value);
    }

    private static String seconds(Long durationMs) {
        return durationMs == null ? "-" : String.format(Locale.ROOT, "%.1f", durationMs / 1000.0);
    }

    private static String ragEvalLink(String datasetKey) {
        return datasetKey == null || datasetKey.trim().isEmpty()
                ? "/rag-eval" : "/rag-eval?datasetKey=" + datasetKey;
    }

    private static NotificationBean notification(String eventKey, String level, String title,
                                                 String content, String link) {
        NotificationBean notice = new NotificationBean();
        notice.setEventKey(eventKey);
        notice.setCategory(NotificationTypes.CATEGORY_RAG_EVAL);
        notice.setLevel(level);
        notice.setTitle(title);
        notice.setContent(content);
        notice.setLink(link);
        return notice;
    }

    private RunCaseData evaluateCase(String runKey, String uid, RagEvalCaseBean item,
                                     String kbName, int topK, String rerankMode, int index) {
        RunCaseData data = new RunCaseData();
        data.index = index;
        data.bean = item;
        long start = System.currentTimeMillis();
        try {
            Boolean rerankEnabled = RagEvalRerankMode.disabled(rerankMode) ? Boolean.FALSE : null;
            Result<KbSearchResult> result =
                    kbRagHolder.search(uid, item.getQuestion(), kbName, topK, rerankEnabled);
            KbSearchResult searchResult = result == null ? null : result.getDatas();
            if (searchResult == null) {
                data.error = result == null ? "检索无响应" : result.getResp_msg();
                return data;
            }
            data.model = searchResult.getModel();
            data.rerankApplied = searchResult.isRerankApplied();
            data.topDoc = null;
            List<KbSearchItem> items = searchResult.getItems();
            if (items != null) {
                for (KbSearchItem hit : items) {
                    if (hit == null) {
                        continue;
                    }
                    String docName = hit.getDocName() == null ? "" : hit.getDocName();
                    data.retrievedDocs.add(docName);
                    data.contents.add(hit.getContent() == null ? "" : hit.getContent());
                    data.hits.add(toHitDto(data.hits.size() + 1, hit, docName));
                }
            }
            if (!data.retrievedDocs.isEmpty()) {
                data.topDoc = data.retrievedDocs.get(0);
            }

            List<String> keywords = RagEvalKeywordCodec.parseKeywords(item.getExpectedKeywords());
            data.keywordEvaluated = !keywords.isEmpty();
            for (int i = 0; i < data.retrievedDocs.size(); i++) {
                if (!item.getExpectedDoc().equals(data.retrievedDocs.get(i))) {
                    continue;
                }
                if (data.firstHitRank == 0) {
                    data.firstHitRank = i + 1;
                }
                if (data.keywordEvaluated && containsAll(data.contents.get(i), keywords)) {
                    data.keywordHit = true;
                    break;
                }
            }
        } catch (Exception e) {
            data.error = "检索异常：" + truncate(e.getMessage());
            log.warn("[RagEval] 单题检索失败, runKey={}, index={}, question={}: {}",
                    runKey, index, item.getQuestion(), e.getMessage());
        } finally {
            data.durationMs = System.currentTimeMillis() - start;
        }
        return data;
    }

    private RagEvaluator.EvalCase toEvalCase(RunCaseData data, Map<String, Long> docIds,
                                              AtomicLong nextDocId) {
        List<Long> retrieved = new ArrayList<Long>(data.retrievedDocs.size());
        for (String docName : data.retrievedDocs) {
            retrieved.add(docId(docIds, nextDocId, docName));
        }
        Set<Long> relevant = Collections.singleton(
                docId(docIds, nextDocId, data.bean.getExpectedDoc()));
        return new RagEvaluator.EvalCase(data.bean.getQuestion(), retrieved, relevant);
    }

    private RagEvalCaseResultBean toResultBean(String uid, String runKey, RunCaseData data,
                                                RagEvaluator.CaseResult caseResult) {
        RagEvalCaseResultBean bean = new RagEvalCaseResultBean();
        bean.setRunKey(runKey);
        bean.setCaseIndex(data.index);
        bean.setQuestion(data.bean.getQuestion());
        bean.setExpectedDoc(data.bean.getExpectedDoc());
        bean.setExpectedKeywords(data.bean.getExpectedKeywords());
        bean.setCategory(data.bean.getCategory());
        bean.setRetrievedDocs(RagEvalKeywordCodec.writeStringList(data.retrievedDocs));
        bean.setRetrievedHits(RagEvalKeywordCodec.writeHits(data.hits));
        bean.setTopDoc(data.topDoc);
        bean.setFirstHitRank(data.firstHitRank);
        bean.setRecall(caseResult.getRecall());
        bean.setPrecision(caseResult.getPrecision());
        bean.setReciprocalRank(caseResult.getReciprocalRank());
        bean.setNdcg(caseResult.getNdcg());
        bean.setKeywordHit(data.keywordHit);
        bean.setKeywordEvaluated(data.keywordEvaluated);
        bean.setDurationMs(data.durationMs);
        bean.setErrorMessage(truncate(data.error));
        bean.setLastuser(uid);
        return bean;
    }

    /**
     * 保留每个命中片段的三路分数与来源：只存文档名时，无法判断漏召是
     * 向量没匹配上、关键词没匹配上，还是精排把正确片段压到了后面。
     */
    private static RagEvalHitDto toHitDto(int rank, KbSearchItem hit, String docName) {
        RagEvalHitDto dto = new RagEvalHitDto();
        dto.setRank(rank);
        dto.setDocName(docName);
        dto.setKbName(hit.getKbName());
        dto.setChunkIndex(hit.getChunkIndex());
        dto.setScore(hit.getScore());
        dto.setDenseScore(hit.getDenseScore());
        dto.setLexicalScore(hit.getLexicalScore());
        dto.setChannel(hit.getChannel());
        dto.setContentPreview(preview(hit.getContent()));
        return dto;
    }

    private static String preview(String content) {
        if (content == null) {
            return null;
        }
        String text = content.trim().replaceAll("\\s+", " ");
        return text.length() <= PREVIEW_CHARS ? text : text.substring(0, PREVIEW_CHARS);
    }

    private static long docId(Map<String, Long> docIds, AtomicLong nextDocId, String docName) {
        String key = docName == null ? "" : docName;
        Long id = docIds.get(key);
        if (id == null) {
            id = nextDocId.getAndIncrement();
            docIds.put(key, id);
        }
        return id;
    }

    /**
     * 关键词命中口径与 CLI 脚本一致：期望文档的某一条命中分块里，
     * 必须同时出现该用例标注的全部关键词（区分大小写的包含匹配）。
     * 不把关键字拆到多个分块里累计——那样无法证明模型拿到的是完整答案段落。
     */
    private static boolean containsAll(String content, List<String> keywords) {
        if (content == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (!content.contains(keyword)) {
                return false;
            }
        }
        return true;
    }

    private RagEvalRunBean findRun(String uid, String runKey) {
        RagEvalRunBean where = new RagEvalRunBean();
        where.setRunKey(runKey);
        where.setLastuser(uid);
        List<RagEvalRunBean> list = runMapper.select(null, where);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private RagEvalDatasetBean findDataset(String uid, String datasetKey) {
        RagEvalDatasetBean where = new RagEvalDatasetBean();
        where.setDatasetKey(datasetKey);
        where.setLastuser(uid);
        List<RagEvalDatasetBean> list = datasetMapper.select(null, where);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private List<RagEvalCaseBean> loadCases(String uid, RagEvalDatasetBean dataset) {
        if (dataset.getCaseSetKey() == null || dataset.getCaseSetKey().trim().isEmpty()) {
            return new ArrayList<RagEvalCaseBean>();
        }
        RagEvalCaseBean where = new RagEvalCaseBean();
        where.setDatasetKey(dataset.getDatasetKey());
        where.setCaseSetKey(dataset.getCaseSetKey());
        where.setLastuser(uid);
        PageRequest page = new PageRequest(1, 201);
        page.setOrder("iid");
        page.setDesc("asc");
        List<RagEvalCaseBean> list = caseMapper.select(page, where);
        return list == null ? new ArrayList<RagEvalCaseBean>() : list;
    }

    private boolean updateRun(String runKey, String uid, RagEvalRunBean data) {
        try {
            RagEvalRunBean where = new RagEvalRunBean();
            where.setRunKey(runKey);
            where.setLastuser(uid);
            return runMapper.update(data, where) > 0;
        } catch (Exception e) {
            log.error("[RagEval] 更新运行记录失败, runKey={}: {}", runKey, e.getMessage(), e);
            return false;
        }
    }

    private void failRun(String runKey, String uid, String message) {
        try {
            RagEvalRunBean run = findRun(uid, runKey);
            long now = System.currentTimeMillis();
            RagEvalRunBean update = new RagEvalRunBean();
            update.setStatus(RagEvalStatus.FAILED);
            update.setErrorMessage(truncate(message));
            update.setFinishedAt(now);
            update.setDurationMs(run != null && run.getStartedAt() != null
                    ? now - run.getStartedAt() : 0L);
            updateRun(runKey, uid, update);
            if (run != null) {
                notificationHolder.push(uid, notification("rag-eval:" + runKey + ":terminal",
                        NotificationTypes.LEVEL_ERROR, "RAG 评估失败",
                        "数据集《" + datasetName(run) + "》评估失败：" + message,
                        ragEvalLink(run.getDatasetKey())));
            }
        } catch (Exception e) {
            log.error("[RagEval] 标记运行失败时出错, runKey={}: {}", runKey, e.getMessage(), e);
        }
    }

    private static String datasetName(RagEvalRunBean run) {
        String name = run == null ? null : run.getDatasetName();
        return name == null || name.trim().isEmpty() ? "评估数据集" : name.trim();
    }

    /**
     * 回收僵死运行：进程重启或线程异常退出后，记录会停留在 queued/running。
     * 不清理会导致该数据集被活动运行唯一索引永久挡住。
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 2 * 60 * 1000)
    public void recoverStaleRuns() {
        long cutoff = System.currentTimeMillis() - STALE_RUN_MS;
        int recovered = 0;
        for (String status : new String[]{RagEvalStatus.QUEUED, RagEvalStatus.RUNNING}) {
            RagEvalRunBean where = new RagEvalRunBean();
            where.setStatus(status);
            PageRequest page = new PageRequest(1, 500);
            page.setOrder("iid");
            page.setDesc("asc");
            List<RagEvalRunBean> runs;
            try {
                runs = runMapper.select(page, where);
            } catch (Exception e) {
                log.warn("[RagEval] 扫描僵死运行失败, status={}: {}", status, e.getMessage());
                continue;
            }
            if (runs == null) {
                continue;
            }
            for (RagEvalRunBean run : runs) {
                if (run.getRunKey() == null || run.getStartedAt() == null
                        || run.getStartedAt() > cutoff) {
                    continue;
                }
                try {
                    RagEvalRunBean update = new RagEvalRunBean();
                    update.setStatus(RagEvalStatus.FAILED);
                    update.setErrorMessage("评估任务超过 30 分钟未完成（可能服务重启），已终止");
                    update.setFinishedAt(System.currentTimeMillis());
                    update.setDurationMs(System.currentTimeMillis() - run.getStartedAt());
                    RagEvalRunBean runWhere = new RagEvalRunBean();
                    runWhere.setRunKey(run.getRunKey());
                    if (runMapper.update(update, runWhere) > 0) {
                        notificationHolder.push(run.getLastuser(),
                                notification("rag-eval:" + run.getRunKey() + ":terminal",
                                        NotificationTypes.LEVEL_ERROR, "RAG 评估超时",
                                        "数据集《" + datasetName(run) + "》超过 30 分钟未完成，"
                                                + "已自动终止，请重新发起。",
                                        ragEvalLink(run.getDatasetKey())));
                        recovered++;
                    }
                } catch (Exception e) {
                    log.warn("[RagEval] 回收僵死运行失败, runKey={}: {}",
                            run.getRunKey(), e.getMessage());
                }
            }
        }
        if (recovered > 0) {
            log.warn("[RagEval] 已回收 {} 个僵死评估运行", recovered);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= TRUNCATE_ERROR_CHARS
                ? value : value.substring(0, TRUNCATE_ERROR_CHARS);
    }

    /** 单题执行中的中间数据，避免把「检索结果 + 评分 + 快照」拆成三层参数传递 */
    private static class RunCaseData {
        private RagEvalCaseBean bean;
        private int index;
        private final List<String> retrievedDocs = new ArrayList<String>();
        private final List<String> contents = new ArrayList<String>();
        private final List<RagEvalHitDto> hits = new ArrayList<RagEvalHitDto>();
        private String model;
        private String topDoc;
        private int firstHitRank;
        private boolean keywordHit;
        private boolean keywordEvaluated;
        private boolean rerankApplied;
        private String error;
        private long durationMs;
    }
}
