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
}
