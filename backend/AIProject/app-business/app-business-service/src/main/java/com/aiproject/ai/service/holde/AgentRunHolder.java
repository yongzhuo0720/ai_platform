package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.agent.dto.AgentRunDetail;
import com.aiproject.ai.service.bean.AgentRunBean;
import com.aiproject.ai.service.bean.AgentStepBean;
import com.aiproject.ai.service.mapper.AgentRunBeanMapper;
import com.aiproject.ai.service.mapper.AgentStepBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Agent 运行记录与步骤明细的只读入口。
 *
 * <p>本子阶段不提供写入接口：运行与步骤由子阶段 2 的执行循环落库，
 * 先把「读」的归属隔离做对，避免执行循环还没写完就先出现越权查询入口。</p>
 */
@Slf4j
@Service
public class AgentRunHolder {

    @Autowired
    AgentRunBeanMapper<AgentRunBean> runMapper;

    @Autowired
    AgentStepBeanMapper<AgentStepBean> stepMapper;

    public Result<List<AgentRunBean>> list(String uid, Long agentIid) {
        if (AgentConfigHolder.blank(uid)) {
            return Result.failed(null, "未登录或登录已过期");
        }
        AgentRunBean where = new AgentRunBean();
        where.setLastuser(uid);
        if (agentIid != null && agentIid > 0) {
            where.setAgentIid(agentIid);
        }
        PageRequest page = new PageRequest(1, 200);
        page.setOrder("iid");
        page.setDesc("desc");
        List<AgentRunBean> list = runMapper.select(page, where);
        return Result.succeed(list == null ? new ArrayList<AgentRunBean>() : list, "查询成功");
    }

    public Result<AgentRunDetail> detail(String uid, String runKey) {
        if (AgentConfigHolder.blank(uid)) {
            return Result.failed(null, "未登录或登录已过期");
        }
        if (AgentConfigHolder.blank(runKey)) {
            return Result.failed(null, "运行标识不能为空");
        }
        AgentRunBean where = new AgentRunBean();
        where.setRunKey(runKey.trim());
        where.setLastuser(uid);
        List<AgentRunBean> runs = runMapper.select(null, where);
        if (runs == null || runs.isEmpty()) {
            return Result.failed(null, "运行记录不存在或不属于当前用户");
        }

        AgentStepBean stepWhere = new AgentStepBean();
        stepWhere.setRunKey(runKey.trim());
        stepWhere.setLastuser(uid);
        List<AgentStepBean> steps = stepMapper.select(null, stepWhere);
        if (steps == null) {
            steps = new ArrayList<AgentStepBean>();
        }
        Collections.sort(steps, new Comparator<AgentStepBean>() {
            @Override
            public int compare(AgentStepBean a, AgentStepBean b) {
                int left = a.getStepNo() == null ? 0 : a.getStepNo();
                int right = b.getStepNo() == null ? 0 : b.getStepNo();
                return Integer.compare(left, right);
            }
        });

        AgentRunDetail detail = new AgentRunDetail();
        detail.setRun(runs.get(0));
        detail.setSteps(steps);
        return Result.succeed(detail, "查询成功");
    }

    /**
     * 供执行循环调用：落一条 running 运行记录。
     * 归属由调用方（已校验过 Agent 归属）传入，这里不再重复查库。
     */
    public void start(String uid, Long agentIid, String question, String runKey) {
        AgentRunBean run = new AgentRunBean();
        run.setRunKey(runKey);
        run.setAgentIid(agentIid);
        run.setStatus("running");
        run.setQuestion(question);
        run.setIterations(0);
        run.setPromptTokens(0L);
        run.setCompletionTokens(0L);
        run.setTotalTokens(0L);
        run.setCostCents(0L);
        run.setStartedAt(System.currentTimeMillis());
        run.setLastuser(uid);
        if (runMapper.install(run) != 1) {
            throw new DataIntegrityViolationException("运行记录创建失败");
        }
    }

    /** 逐步落追踪明细；工具参数与结果已在执行器侧做过截断 */
    public AgentStepBean addStep(String uid, String runKey, int stepNo, int iteration,
                        String stepType, String toolName, String args, String result,
                        String status, long promptTokens, long completionTokens,
                        long costCents, long latencyMs, String error, String requestId) {
        AgentStepBean step = new AgentStepBean();
        step.setRunKey(runKey);
        step.setStepNo(stepNo);
        step.setIteration(iteration);
        step.setStepType(stepType);
        step.setToolName(toolName);
        step.setToolArgs(args);
        step.setToolResult(result);
        step.setStatus(status);
        step.setPromptTokens(promptTokens);
        step.setCompletionTokens(completionTokens);
        step.setCostCents(costCents);
        step.setLatencyMs(latencyMs);
        step.setErrorMessage(error);
        step.setRequestId(requestId);
        step.setLastuser(uid);
        if (stepMapper.install(step) != 1) {
            throw new DataIntegrityViolationException("运行步骤保存失败");
        }
        return step;
    }

    /**
     * 回收僵死运行：进程重启、线程被卡死或客户端断开后执行循环没能收尾时，
     * 记录会永远停在 queued/running，占着 idx_agentrun_active，导致这个
     * 用户在这个 Agent 上再也发不出新一轮请求。
     *
     * <p>按「状态 CAS」更新：只有仍然停留在原状态的记录才会被改成 failed；
     * 如果执行循环刚好完成并写了 succeeded，这里更新 0 行，绝不会覆盖正常结果。</p>
     *
     * @return 实际回收的条数
     */
    public int recoverStale(long staleBefore, long now, String errorMessage) {
        int recovered = 0;
        for (String status : new String[]{"queued", "running"}) {
            AgentRunBean where = new AgentRunBean();
            where.setStatus(status);
            PageRequest page = new PageRequest(1, 500);
            page.setOrder("iid");
            page.setDesc("asc");
            List<AgentRunBean> runs;
            try {
                runs = runMapper.select(page, where);
            } catch (Exception e) {
                log.warn("[Agent] 查询僵死运行失败, status={}: {}", status, e.getMessage());
                continue;
            }
            if (runs == null) {
                continue;
            }
            for (AgentRunBean run : runs) {
                if (run == null || AgentConfigHolder.blank(run.getRunKey())
                        || run.getStartedAt() == null || run.getStartedAt() >= staleBefore) {
                    continue;
                }
                AgentRunBean patch = new AgentRunBean();
                patch.setStatus("failed");
                patch.setErrorMessage(errorMessage);
                patch.setFinishedAt(now);
                patch.setDurationMs(Math.max(0L, now - run.getStartedAt()));
                AgentRunBean casWhere = new AgentRunBean();
                casWhere.setRunKey(run.getRunKey());
                casWhere.setLastuser(run.getLastuser());
                casWhere.setStatus(status);
                try {
                    if (runMapper.update(patch, casWhere) > 0) {
                        recovered++;
                        log.warn("[Agent] 回收僵死运行: runKey={}, status={}, ageMs={}",
                                run.getRunKey(), status, now - run.getStartedAt());
                    }
                } catch (Exception e) {
                    log.warn("[Agent] 回收僵死运行失败, runKey={}: {}",
                            run.getRunKey(), e.getMessage());
                }
            }
        }
        return recovered;
    }

    /** 收尾：只 SET 汇总字段，where 带 runKey + 归属 */
    public void finish(String uid, String runKey, String status, int iterations,
                       long prompt, long completion, long cost, long durationMs,
                       String answer, String error) {
        AgentRunBean patch = new AgentRunBean();
        patch.setStatus(status);
        patch.setIterations(iterations);
        patch.setPromptTokens(prompt);
        patch.setCompletionTokens(completion);
        patch.setTotalTokens(prompt + completion);
        patch.setCostCents(cost);
        patch.setFinishedAt(System.currentTimeMillis());
        patch.setDurationMs(durationMs);
        patch.setAnswer(answer);
        patch.setErrorMessage(error);
        AgentRunBean where = new AgentRunBean();
        where.setRunKey(runKey);
        where.setLastuser(uid);
        if (runMapper.update(patch, where) != 1) {
            throw new DataIntegrityViolationException("运行汇总保存失败");
        }
    }
}
