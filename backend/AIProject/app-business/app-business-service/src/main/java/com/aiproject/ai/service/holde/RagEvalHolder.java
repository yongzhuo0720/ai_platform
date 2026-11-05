package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.KbDocBean;
import com.aiproject.ai.service.bean.RagEvalCaseBean;
import com.aiproject.ai.service.bean.RagEvalCaseResultBean;
import com.aiproject.ai.service.bean.RagEvalDatasetBean;
import com.aiproject.ai.service.bean.RagEvalRunBean;
import com.aiproject.ai.service.mapper.KbDocBeanMapper;
import com.aiproject.ai.service.mapper.RagEvalCaseBeanMapper;
import com.aiproject.ai.service.mapper.RagEvalCaseResultBeanMapper;
import com.aiproject.ai.service.mapper.RagEvalDatasetBeanMapper;
import com.aiproject.ai.service.mapper.RagEvalRunBeanMapper;
import com.aiproject.ai.service.rag.RagEvalKeywordCodec;
import com.aiproject.ai.service.rag.RagEvalRunner;
import com.aiproject.ai.service.rag.RagEvalStatus;
import com.aiproject.ai.service.rag.dto.RagEvalCaseDto;
import com.aiproject.ai.service.rag.dto.RagEvalCaseInput;
import com.aiproject.ai.service.rag.dto.RagEvalCaseResultDto;
import com.aiproject.ai.service.rag.dto.RagEvalDatasetDetail;
import com.aiproject.ai.service.rag.dto.RagEvalDatasetRequest;
import com.aiproject.ai.service.rag.dto.RagEvalRunDetail;
import com.aiproject.ai.service.rag.dto.RagEvalRunRequest;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * RAG 评估数据集与运行记录的业务入口。
 *
 * <p>指标算法不在这里实现：检索质量指标统一由 {@link com.aiproject.ai.service.rag.RagEvaluator}
 * 计算，实际检索统一走 {@link KbRagHolder#search}。这个 Holder 只负责
 * 校验、归属隔离、持久化编排和异步任务提交，避免出现「产品内一套口径、
 * CI 脚本另一套口径」的双实现。</p>
 *
 * <p>权限模型：数据集与运行记录都是用户私有资产，所有查询/修改条件都带
 * {@code lastuser=当前用户}；没有管理员越权入口，管理员要评估也只能建自己的数据集。</p>
 */
@Slf4j
@Service
public class RagEvalHolder {

    private static final int MAX_CASES = 200;
    private static final int MAX_QUESTION_CHARS = 500;
    private static final int MAX_DOC_NAME_CHARS = 200;
    private static final int MAX_KEYWORDS = 10;
    private static final int MAX_KEYWORD_CHARS = 50;
    private static final int MAX_CATEGORY_CHARS = 32;
    private static final int MAX_ACTIVE_RUNS_PER_USER = 2;
    private static final int DEFAULT_TOP_K = 5;

    @Autowired
    RagEvalDatasetBeanMapper<RagEvalDatasetBean> datasetMapper;

    @Autowired
    RagEvalCaseBeanMapper<RagEvalCaseBean> caseMapper;

    @Autowired
    RagEvalRunBeanMapper<RagEvalRunBean> runMapper;

    @Autowired
    RagEvalCaseResultBeanMapper<RagEvalCaseResultBean> resultMapper;

    @Autowired
    KbDocBeanMapper<KbDocBean> kbDocBeanMapper;

    @Autowired
    RagEvalRunner ragEvalRunner;

    @Autowired
    @Qualifier("ragEvalExecutor")
    TaskExecutor ragEvalExecutor;

    public Result<List<RagEvalDatasetBean>> listDatasets(String uid) {
        if (notLoggedIn(uid)) {
            return Result.failed(null, "未登录或登录已过期");
        }
        try {
            RagEvalDatasetBean where = new RagEvalDatasetBean();
            where.setLastuser(uid);
            PageRequest page = new PageRequest(1, 200);
            page.setOrder("iid");
            page.setDesc("desc");
            List<RagEvalDatasetBean> list = datasetMapper.select(page, where);
            return Result.succeed(list == null ? new ArrayList<RagEvalDatasetBean>() : list, "查询成功");
        } catch (Exception e) {
            log.error("[RagEval] 查询数据集失败, uid={}: {}", uid, e.getMessage(), e);
            return Result.failed(null, "查询数据集失败，请稍后重试");
        }
    }

    public Result<RagEvalDatasetDetail> getDataset(String uid, String datasetKey) {
        if (notLoggedIn(uid)) {
            return Result.failed(null, "未登录或登录已过期");
        }
        if (blank(datasetKey)) {
            return Result.failed(null, "数据集标识不能为空");
        }
        try {
            RagEvalDatasetBean dataset = findDataset(uid, datasetKey.trim());
            if (dataset == null) {
                return Result.failed(null, "数据集不存在或不属于当前用户");
            }
            RagEvalDatasetDetail detail = new RagEvalDatasetDetail();
            detail.setDataset(dataset);
            detail.setCases(toCaseDtos(loadActiveCases(uid, dataset)));
            return Result.succeed(detail, "查询成功");
        } catch (Exception e) {
            log.error("[RagEval] 查询数据集详情失败, uid={}, key={}: {}",
                    uid, datasetKey, e.getMessage(), e);
            return Result.failed(null, "查询数据集失败，请稍后重试");
        }
    }

    public Result<String> createDataset(String uid, RagEvalDatasetRequest data) {
        if (notLoggedIn(uid)) {
            return Result.failed(null, "未登录或登录已过期");
        }
        String invalid = normalize(data);
        if (invalid != null) {
            return Result.failed(null, invalid);
        }
        try {
            if (findDatasetByName(uid, data.getName()) != null) {
                return Result.failed(null, "数据集名称已存在，请换一个名称");
            }
            String datasetKey = UUID.randomUUID().toString();
            String caseSetKey = UUID.randomUUID().toString();

            RagEvalDatasetBean dataset = new RagEvalDatasetBean();
            dataset.setDatasetKey(datasetKey);
            dataset.setName(data.getName());
            dataset.setKbName(data.getKbName());
            dataset.setDescription(data.getDescription());
            dataset.setCaseCount(data.getCases().size());
            dataset.setCaseSetKey(caseSetKey);
            dataset.setLastuser(uid);
            if (datasetMapper.install(dataset) <= 0) {
                return Result.failed(null, "创建数据集失败，请稍后重试");
            }
            try {
                insertCases(uid, datasetKey, caseSetKey, data.getCases());
            } catch (Exception e) {
                // 没有事务：用例写入失败时回收刚插入的数据集，避免留下一个空壳数据集
                deleteCases(uid, datasetKey, caseSetKey);
                deleteDatasetRow(uid, datasetKey);
                log.error("[RagEval] 创建数据集时写入用例失败, uid={}, key={}: {}",
                        uid, datasetKey, e.getMessage(), e);
                return Result.failed(null, "创建数据集失败：用例保存失败，请重试");
            }
            return Result.succeed(datasetKey, "数据集已创建，共 " + data.getCases().size() + " 条用例");
        } catch (Exception e) {
            log.error("[RagEval] 创建数据集失败, uid={}: {}", uid, e.getMessage(), e);
            return Result.failed(null, "创建数据集失败，请检查名称是否重复后重试");
        }
    }

    public Result<Boolean> updateDataset(String uid, RagEvalDatasetRequest data) {
        if (notLoggedIn(uid)) {
            return Result.failed(false, "未登录或登录已过期");
        }
        if (data == null || blank(data.getDatasetKey())) {
            return Result.failed(false, "数据集标识不能为空");
        }
        String invalid = normalize(data);
        if (invalid != null) {
            return Result.failed(false, invalid);
        }
        String datasetKey = data.getDatasetKey().trim();
        try {
            RagEvalDatasetBean existing = findDataset(uid, datasetKey);
            if (existing == null) {
                return Result.failed(false, "数据集不存在或不属于当前用户");
            }
            RagEvalDatasetBean sameName = findDatasetByName(uid, data.getName());
            if (sameName != null && !datasetKey.equals(sameName.getDatasetKey())) {
                return Result.failed(false, "数据集名称已存在，请换一个名称");
            }

            // 写时复制：先写新用例集，切换成功后再删旧集。中途失败时旧用例仍然可用。
            String newCaseSetKey = UUID.randomUUID().toString();
            try {
                insertCases(uid, datasetKey, newCaseSetKey, data.getCases());
            } catch (Exception e) {
                deleteCases(uid, datasetKey, newCaseSetKey);
                log.error("[RagEval] 编辑数据集时写入用例失败, uid={}, key={}: {}",
                        uid, datasetKey, e.getMessage(), e);
                return Result.failed(false, "保存失败：用例写入失败，原数据集未改动");
            }

            RagEvalDatasetBean update = new RagEvalDatasetBean();
            update.setName(data.getName());
            update.setKbName(data.getKbName());
            update.setDescription(data.getDescription());
            update.setCaseCount(data.getCases().size());
            update.setCaseSetKey(newCaseSetKey);
            update.setLastuser(uid);
            RagEvalDatasetBean where = new RagEvalDatasetBean();
            where.setDatasetKey(datasetKey);
            where.setLastuser(uid);
            int affected;
            try {
                affected = datasetMapper.update(update, where);
            } catch (Exception e) {
                deleteCases(uid, datasetKey, newCaseSetKey);
                log.error("[RagEval] 切换用例集失败, uid={}, key={}: {}", uid, datasetKey, e.getMessage(), e);
                return Result.failed(false, "保存失败，原数据集未改动");
            }
            if (affected <= 0) {
                deleteCases(uid, datasetKey, newCaseSetKey);
                return Result.failed(false, "数据集已被删除或状态已变化，请刷新后重试");
            }

            String oldCaseSetKey = existing.getCaseSetKey();
            if (!blank(oldCaseSetKey)) {
                try {
                    deleteCases(uid, datasetKey, oldCaseSetKey);
                } catch (Exception e) {
                    // 旧用例已经成为历史版本，删除失败只留下不可见数据，不影响本次保存
                    log.warn("[RagEval] 旧用例集清理失败, uid={}, key={}, caseSetKey={}: {}",
                            uid, datasetKey, oldCaseSetKey, e.getMessage());
                }
            }
            return Result.succeed(true, "保存成功，共 " + data.getCases().size() + " 条用例");
        } catch (Exception e) {
            log.error("[RagEval] 编辑数据集失败, uid={}, key={}: {}", uid, datasetKey, e.getMessage(), e);
            return Result.failed(false, "保存失败，请稍后重试");
        }
    }

    public Result<Boolean> deleteDataset(String uid, String datasetKey) {
        if (notLoggedIn(uid)) {
            return Result.failed(false, "未登录或登录已过期");
        }
        if (blank(datasetKey)) {
            return Result.failed(false, "数据集标识不能为空");
        }
        String key = datasetKey.trim();
        try {
            RagEvalDatasetBean dataset = findDataset(uid, key);
            if (dataset == null) {
                return Result.failed(false, "数据集不存在或不属于当前用户");
            }
            List<RagEvalRunBean> runs = selectRuns(uid, key, 200);
            for (RagEvalRunBean run : runs) {
                if (RagEvalStatus.isActive(run.getStatus())) {
                    return Result.failed(false, "该数据集有正在进行的评估，请等待完成后再删除");
                }
            }
            for (RagEvalRunBean run : runs) {
                if (blank(run.getRunKey())) {
                    continue;
                }
                deleteResults(uid, run.getRunKey());
                RagEvalRunBean runWhere = new RagEvalRunBean();
                runWhere.setRunKey(run.getRunKey());
                runWhere.setLastuser(uid);
                runMapper.delete(runWhere);
            }
            RagEvalCaseBean caseWhere = new RagEvalCaseBean();
            caseWhere.setDatasetKey(key);
            caseWhere.setLastuser(uid);
            caseMapper.delete(caseWhere);

            if (deleteDatasetRow(uid, key) <= 0) {
                return Result.failed(false, "数据集已被删除，请刷新后重试");
            }
            return Result.succeed(true, "数据集及其运行记录已删除");
        } catch (Exception e) {
            log.error("[RagEval] 删除数据集失败, uid={}, key={}: {}", uid, datasetKey, e.getMessage(), e);
            return Result.failed(false, "删除失败，可能有部分记录已清理，请刷新后重试");
        }
    }

    public Result<List<RagEvalRunBean>> listRuns(String uid, String datasetKey) {
        if (notLoggedIn(uid)) {
            return Result.failed(null, "未登录或登录已过期");
        }
        try {
            List<RagEvalRunBean> runs = selectRuns(uid, datasetKey, 100);
            return Result.succeed(runs, "查询成功");
        } catch (Exception e) {
            log.error("[RagEval] 查询运行记录失败, uid={}: {}", uid, e.getMessage(), e);
            return Result.failed(null, "查询运行记录失败，请稍后重试");
        }
    }

    public Result<RagEvalRunDetail> getRun(String uid, String runKey) {
        if (notLoggedIn(uid)) {
            return Result.failed(null, "未登录或登录已过期");
        }
        if (blank(runKey)) {
            return Result.failed(null, "运行标识不能为空");
        }
        try {
            RagEvalRunBean run = findRun(uid, runKey.trim());
            if (run == null) {
                return Result.failed(null, "运行记录不存在或不属于当前用户");
            }
            RagEvalCaseResultBean where = new RagEvalCaseResultBean();
            where.setRunKey(run.getRunKey());
            where.setLastuser(uid);
            PageRequest page = new PageRequest(1, MAX_CASES + 1);
            page.setOrder("caseIndex");
            page.setDesc("asc");
            List<RagEvalCaseResultBean> rawResults = resultMapper.select(page, where);
            List<RagEvalCaseResultDto> results = new ArrayList<RagEvalCaseResultDto>();
            if (rawResults != null) {
                for (RagEvalCaseResultBean bean : rawResults) {
                    results.add(toResultDto(bean));
                }
            }

            RagEvalRunDetail detail = new RagEvalRunDetail();
            detail.setRun(run);
            detail.setResults(results);
            detail.setBaseline(findBaseline(uid, run));
            return Result.succeed(detail, "查询成功");
        } catch (Exception e) {
            log.error("[RagEval] 查询运行详情失败, uid={}, runKey={}: {}",
                    uid, runKey, e.getMessage(), e);
            return Result.failed(null, "查询运行详情失败，请稍后重试");
        }
    }

    public Result<Boolean> deleteRun(String uid, String runKey) {
        if (notLoggedIn(uid)) {
            return Result.failed(false, "未登录或登录已过期");
        }
        if (blank(runKey)) {
            return Result.failed(false, "运行标识不能为空");
        }
        String key = runKey.trim();
        try {
            RagEvalRunBean run = findRun(uid, key);
            if (run == null) {
                return Result.failed(false, "运行记录不存在或不属于当前用户");
            }
            if (RagEvalStatus.isActive(run.getStatus())) {
                return Result.failed(false, "评估正在进行，暂不能删除运行记录");
            }
            deleteResults(uid, key);
            RagEvalRunBean where = new RagEvalRunBean();
            where.setRunKey(key);
            where.setLastuser(uid);
            if (runMapper.delete(where) <= 0) {
                return Result.failed(false, "运行记录已被删除，请刷新后重试");
            }
            return Result.succeed(true, "运行记录已删除");
        } catch (Exception e) {
            log.error("[RagEval] 删除运行记录失败, uid={}, runKey={}: {}", uid, runKey, e.getMessage(), e);
            return Result.failed(false, "删除失败，请稍后重试");
        }
    }

    /**
     * 启动一次异步评估。
     *
     * <p>启动前完成三件事，避免把错误留到后台线程：用例与知识库文档必须对得上、
     * 同一数据集不能并发跑、单用户活动任务不超过 2 个。真正耗时的检索在
     * {@link RagEvalRunner} 中执行，接口立即返回 runKey 供前端轮询进度。</p>
     */
    public Result<String> startRun(String uid, RagEvalRunRequest data) {
        if (notLoggedIn(uid)) {
            return Result.failed(null, "未登录或登录已过期");
        }
        if (data == null || blank(data.getDatasetKey())) {
            return Result.failed(null, "数据集标识不能为空");
        }
        int topK = data.getTopK() == null ? DEFAULT_TOP_K : data.getTopK();
        if (topK < 1 || topK > KbRagHolder.MAX_TOP_K) {
            return Result.failed(null, "截断位置 k 必须在 1-" + KbRagHolder.MAX_TOP_K + " 之间");
        }
        String datasetKey = data.getDatasetKey().trim();
        try {
            RagEvalDatasetBean dataset = findDataset(uid, datasetKey);
            if (dataset == null) {
                return Result.failed(null, "数据集不存在或不属于当前用户");
            }
            List<RagEvalCaseBean> cases = loadActiveCases(uid, dataset);
            if (cases.isEmpty()) {
                return Result.failed(null, "数据集中没有用例，请先编辑数据集");
            }

            String missingDocs = findMissingDocs(uid, dataset.getKbName(), cases);
            if (missingDocs != null) {
                return Result.failed(null, missingDocs);
            }
            if (hasActiveRun(uid, datasetKey)) {
                return Result.failed(null, "该数据集有正在进行的评估，请等待完成后再试");
            }
            if (countActiveRuns(uid) >= MAX_ACTIVE_RUNS_PER_USER) {
                return Result.failed(null,
                        "同时最多运行 " + MAX_ACTIVE_RUNS_PER_USER + " 个评估任务，请等待当前任务完成");
            }

            String runKey = UUID.randomUUID().toString();
            RagEvalRunBean run = new RagEvalRunBean();
            run.setRunKey(runKey);
            run.setDatasetKey(datasetKey);
            run.setDatasetName(dataset.getName());
            run.setKbName(dataset.getKbName());
            run.setTopK(topK);
            run.setStatus(RagEvalStatus.QUEUED);
            run.setTotal(cases.size());
            run.setProcessed(0);
            run.setStartedAt(System.currentTimeMillis());
            run.setLastuser(uid);
            try {
                if (runMapper.install(run) <= 0) {
                    return Result.failed(null, "启动评估失败，请稍后重试");
                }
            } catch (Exception e) {
                // 并发启动时由 (lastuser, datasetKey) 的活动运行唯一索引兜底
                log.warn("[RagEval] 创建运行记录失败, uid={}, datasetKey={}: {}",
                        uid, datasetKey, e.getMessage());
                return Result.failed(null, "该数据集已有正在进行的评估，请稍后刷新");
            }

            try {
                ragEvalExecutor.execute(() -> ragEvalRunner.execute(runKey, uid));
            } catch (Exception e) {
                log.error("[RagEval] 评估任务提交失败, uid={}, runKey={}: {}",
                        uid, runKey, e.getMessage(), e);
                failQueuedRun(uid, runKey, "评估任务队列繁忙，请稍后重试");
                return Result.failed(null, "评估任务队列繁忙，请稍后重试");
            }
            return Result.succeed(runKey, "评估已启动，共 " + cases.size() + " 条用例");
        } catch (Exception e) {
            log.error("[RagEval] 启动评估失败, uid={}, datasetKey={}: {}",
                    uid, datasetKey, e.getMessage(), e);
            return Result.failed(null, "启动评估失败，请稍后重试");
        }
    }

    // ------------------------------------------------------------------
    // 校验与读写辅助
    // ------------------------------------------------------------------

    /**
     * 校验并规范化请求，返回 null 表示通过。
     * <p>所有长度上限都在服务端强制执行：前端校验只是体验，不能作为安全边界。</p>
     */
    private String normalize(RagEvalDatasetRequest data) {
        if (data == null) {
            return "请求不能为空";
        }
        String name = trim(data.getName());
        if (name.isEmpty()) {
            return "数据集名称不能为空";
        }
        if (name.length() > 100) {
            return "数据集名称最多 100 字符";
        }
        String kbName = trim(data.getKbName());
        if (kbName.isEmpty()) {
            kbName = "默认知识库";
        }
        if (kbName.length() > 100) {
            return "知识库名称最多 100 字符";
        }
        String description = data.getDescription() == null ? "" : data.getDescription().trim();
        if (description.length() > 2000) {
            return "数据集说明最多 2000 字符";
        }
        List<RagEvalCaseInput> cases = data.getCases();
        if (cases == null || cases.isEmpty()) {
            return "至少需要一条评估用例";
        }
        if (cases.size() > MAX_CASES) {
            return "单次评估最多 " + MAX_CASES + " 条用例";
        }
        Set<String> questions = new HashSet<String>();
        for (int i = 0; i < cases.size(); i++) {
            RagEvalCaseInput item = cases.get(i);
            String prefix = "第 " + (i + 1) + " 条用例";
            if (item == null) {
                return prefix + "不能为空";
            }
            String question = trim(item.getQuestion());
            if (question.isEmpty()) {
                return prefix + "的问题不能为空";
            }
            if (question.length() > MAX_QUESTION_CHARS) {
                return prefix + "的问题最多 " + MAX_QUESTION_CHARS + " 字符";
            }
            if (!questions.add(question)) {
                return "存在重复问题：" + question;
            }
            String expectedDoc = trim(item.getExpectedDoc());
            if (expectedDoc.isEmpty()) {
                return prefix + "的期望文档不能为空";
            }
            if (expectedDoc.length() > MAX_DOC_NAME_CHARS) {
                return prefix + "的期望文档名最多 " + MAX_DOC_NAME_CHARS + " 字符";
            }
            List<String> keywords = new ArrayList<String>();
            Set<String> dedup = new LinkedHashSet<String>();
            if (item.getKeywords() != null) {
                for (String keyword : item.getKeywords()) {
                    String value = trim(keyword);
                    if (value.isEmpty()) {
                        continue;
                    }
                    if (value.length() > MAX_KEYWORD_CHARS) {
                        return prefix + "的关键词最多 " + MAX_KEYWORD_CHARS + " 字符";
                    }
                    dedup.add(value);
                }
                keywords.addAll(dedup);
            }
            if (keywords.size() > MAX_KEYWORDS) {
                return prefix + "的关键词最多 " + MAX_KEYWORDS + " 个";
            }
            String category = trim(item.getCategory());
            if (category.isEmpty()) {
                category = "未分类";
            }
            if (category.length() > MAX_CATEGORY_CHARS) {
                return prefix + "的题型最多 " + MAX_CATEGORY_CHARS + " 字符";
            }
            item.setQuestion(question);
            item.setExpectedDoc(expectedDoc);
            item.setKeywords(keywords);
            item.setCategory(category);
        }
        data.setName(name);
        data.setKbName(kbName);
        data.setDescription(description);
        return null;
    }

    /**
     * 启动前校验期望文档确实存在：文档名对不上是最常见的配置错误。
     * <p>先失败再跑，避免白白调用几十次付费的 embedding 之后才得到一堆 0 分。</p>
     */
    private String findMissingDocs(String uid, String kbName, List<RagEvalCaseBean> cases) {
        KbDocBean where = new KbDocBean();
        where.setLastuser(uid);
        where.setKbName(kbName);
        List<KbDocBean> docs = kbDocBeanMapper.select(null, where);
        if (docs == null || docs.isEmpty()) {
            return "知识库「" + kbName + "」下没有文档，请先在知识检索页上传评估语料";
        }
        Set<String> names = new HashSet<String>();
        for (KbDocBean doc : docs) {
            if (doc.getName() != null) {
                names.add(doc.getName().trim());
            }
        }
        List<String> missing = new ArrayList<String>();
        for (RagEvalCaseBean item : cases) {
            if (!names.contains(trim(item.getExpectedDoc()))) {
                missing.add(item.getExpectedDoc());
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        List<String> preview = missing.size() > 5 ? missing.subList(0, 5) : missing;
        return "以下期望文档在知识库「" + kbName + "」中不存在：" + String.join("、", preview)
                + (missing.size() > 5 ? " 等 " + missing.size() + " 个" : "")
                + "；请先上传文档或调整用例";
    }

    private RagEvalDatasetBean findDataset(String uid, String datasetKey) {
        RagEvalDatasetBean where = new RagEvalDatasetBean();
        where.setDatasetKey(datasetKey);
        where.setLastuser(uid);
        List<RagEvalDatasetBean> list = datasetMapper.select(null, where);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private RagEvalDatasetBean findDatasetByName(String uid, String name) {
        RagEvalDatasetBean where = new RagEvalDatasetBean();
        where.setName(name);
        where.setLastuser(uid);
        List<RagEvalDatasetBean> list = datasetMapper.select(null, where);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private RagEvalRunBean findRun(String uid, String runKey) {
        RagEvalRunBean where = new RagEvalRunBean();
        where.setRunKey(runKey);
        where.setLastuser(uid);
        List<RagEvalRunBean> list = runMapper.select(null, where);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private List<RagEvalCaseBean> loadActiveCases(String uid, RagEvalDatasetBean dataset) {
        if (dataset == null || blank(dataset.getCaseSetKey())) {
            return new ArrayList<RagEvalCaseBean>();
        }
        RagEvalCaseBean where = new RagEvalCaseBean();
        where.setDatasetKey(dataset.getDatasetKey());
        where.setCaseSetKey(dataset.getCaseSetKey());
        where.setLastuser(uid);
        PageRequest page = new PageRequest(1, MAX_CASES + 1);
        page.setOrder("iid");
        page.setDesc("asc");
        List<RagEvalCaseBean> list = caseMapper.select(page, where);
        return list == null ? new ArrayList<RagEvalCaseBean>() : list;
    }

    private void insertCases(String uid, String datasetKey, String caseSetKey,
                             List<RagEvalCaseInput> cases) throws Exception {
        for (RagEvalCaseInput item : cases) {
            RagEvalCaseBean bean = new RagEvalCaseBean();
            bean.setDatasetKey(datasetKey);
            bean.setCaseSetKey(caseSetKey);
            bean.setQuestion(item.getQuestion());
            bean.setExpectedDoc(item.getExpectedDoc());
            bean.setExpectedKeywords(RagEvalKeywordCodec.writeKeywords(item.getKeywords()));
            bean.setCategory(item.getCategory());
            bean.setLastuser(uid);
            if (caseMapper.install(bean) <= 0) {
                throw new IllegalStateException("用例写入失败");
            }
        }
    }

    private void deleteCases(String uid, String datasetKey, String caseSetKey) {
        if (blank(caseSetKey)) {
            return;
        }
        RagEvalCaseBean where = new RagEvalCaseBean();
        where.setDatasetKey(datasetKey);
        where.setCaseSetKey(caseSetKey);
        where.setLastuser(uid);
        caseMapper.delete(where);
    }

    private int deleteDatasetRow(String uid, String datasetKey) {
        RagEvalDatasetBean where = new RagEvalDatasetBean();
        where.setDatasetKey(datasetKey);
        where.setLastuser(uid);
        return datasetMapper.delete(where);
    }

    private void deleteResults(String uid, String runKey) {
        RagEvalCaseResultBean where = new RagEvalCaseResultBean();
        where.setRunKey(runKey);
        where.setLastuser(uid);
        resultMapper.delete(where);
    }

    private List<RagEvalRunBean> selectRuns(String uid, String datasetKey, int limit) {
        RagEvalRunBean where = new RagEvalRunBean();
        where.setLastuser(uid);
        if (!blank(datasetKey)) {
            where.setDatasetKey(datasetKey.trim());
        }
        PageRequest page = new PageRequest(1, limit);
        page.setOrder("iid");
        page.setDesc("desc");
        List<RagEvalRunBean> list = runMapper.select(page, where);
        return list == null ? new ArrayList<RagEvalRunBean>() : list;
    }

    private boolean hasActiveRun(String uid, String datasetKey) {
        return !selectActiveRuns(uid, datasetKey, RagEvalStatus.QUEUED).isEmpty()
                || !selectActiveRuns(uid, datasetKey, RagEvalStatus.RUNNING).isEmpty();
    }

    private int countActiveRuns(String uid) {
        return selectActiveRuns(uid, null, RagEvalStatus.QUEUED).size()
                + selectActiveRuns(uid, null, RagEvalStatus.RUNNING).size();
    }

    private List<RagEvalRunBean> selectActiveRuns(String uid, String datasetKey, String status) {
        RagEvalRunBean where = new RagEvalRunBean();
        where.setLastuser(uid);
        where.setStatus(status);
        if (!blank(datasetKey)) {
            where.setDatasetKey(datasetKey);
        }
        List<RagEvalRunBean> list = runMapper.select(null, where);
        return list == null ? Collections.<RagEvalRunBean>emptyList() : list;
    }

    private RagEvalRunBean findBaseline(String uid, RagEvalRunBean current) {
        List<RagEvalRunBean> runs = selectRuns(uid, current.getDatasetKey(), 100);
        for (RagEvalRunBean candidate : runs) {
            if (current.getRunKey().equals(candidate.getRunKey())) {
                continue;
            }
            if (current.getIid() != null && candidate.getIid() != null
                    && candidate.getIid() >= current.getIid()) {
                continue;
            }
            if (RagEvalStatus.isComparable(candidate.getStatus())) {
                return candidate;
            }
        }
        return null;
    }

    private void failQueuedRun(String uid, String runKey, String message) {
        try {
            RagEvalRunBean update = new RagEvalRunBean();
            update.setStatus(RagEvalStatus.FAILED);
            update.setErrorMessage(message);
            update.setFinishedAt(System.currentTimeMillis());
            update.setDurationMs(0L);
            RagEvalRunBean where = new RagEvalRunBean();
            where.setRunKey(runKey);
            where.setLastuser(uid);
            runMapper.update(update, where);
        } catch (Exception e) {
            log.error("[RagEval] 标记运行失败时出错, runKey={}: {}", runKey, e.getMessage(), e);
        }
    }

    private List<RagEvalCaseDto> toCaseDtos(List<RagEvalCaseBean> cases) {
        List<RagEvalCaseDto> list = new ArrayList<RagEvalCaseDto>();
        for (RagEvalCaseBean bean : cases) {
            RagEvalCaseDto dto = new RagEvalCaseDto();
            dto.setIid(bean.getIid());
            dto.setQuestion(bean.getQuestion());
            dto.setExpectedDoc(bean.getExpectedDoc());
            dto.setKeywords(RagEvalKeywordCodec.parseKeywords(bean.getExpectedKeywords()));
            dto.setCategory(bean.getCategory());
            list.add(dto);
        }
        return list;
    }

    private RagEvalCaseResultDto toResultDto(RagEvalCaseResultBean bean) {
        RagEvalCaseResultDto dto = new RagEvalCaseResultDto();
        dto.setCaseIndex(bean.getCaseIndex());
        dto.setQuestion(bean.getQuestion());
        dto.setExpectedDoc(bean.getExpectedDoc());
        dto.setKeywords(RagEvalKeywordCodec.parseKeywords(bean.getExpectedKeywords()));
        dto.setCategory(bean.getCategory());
        dto.setRetrievedDocs(RagEvalKeywordCodec.parseStringList(bean.getRetrievedDocs()));
        dto.setTopDoc(bean.getTopDoc());
        dto.setFirstHitRank(bean.getFirstHitRank());
        dto.setRecall(bean.getRecall());
        dto.setPrecision(bean.getPrecision());
        dto.setReciprocalRank(bean.getReciprocalRank());
        dto.setNdcg(bean.getNdcg());
        dto.setKeywordHit(bean.getKeywordHit());
        dto.setKeywordEvaluated(bean.getKeywordEvaluated());
        dto.setDurationMs(bean.getDurationMs());
        dto.setErrorMessage(bean.getErrorMessage());
        return dto;
    }

    private static boolean notLoggedIn(String uid) {
        return uid == null || uid.trim().isEmpty();
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
