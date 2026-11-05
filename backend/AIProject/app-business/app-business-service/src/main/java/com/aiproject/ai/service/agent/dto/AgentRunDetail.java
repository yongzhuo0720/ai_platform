package com.aiproject.ai.service.agent.dto;

import com.aiproject.ai.service.bean.AgentRunBean;
import com.aiproject.ai.service.bean.AgentStepBean;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Agent 运行详情：汇总 + 按 stepNo 排序的步骤明细。 */
@Data
public class AgentRunDetail {

    private AgentRunBean run;

    private List<AgentStepBean> steps = new ArrayList<AgentStepBean>();
}
