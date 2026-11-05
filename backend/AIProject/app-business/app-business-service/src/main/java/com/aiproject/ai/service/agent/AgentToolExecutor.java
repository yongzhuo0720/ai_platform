package com.aiproject.ai.service.agent;

import com.aiproject.ai.service.bean.AgentToolBean;
import com.aiproject.ai.service.gateway.dto.KbSearchItem;
import com.aiproject.ai.service.gateway.dto.KbSearchResult;
import com.aiproject.ai.service.holde.KbRagHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.heneng.hnsp.common.croe.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Agent 工具执行器。
 *
 * <p>本轮只开放两类安全的内置工具：知识库检索（复用 {@link KbRagHolder#search}）
 * 与当前时间。HTTP 工具的目标地址白名单（SSRF 防护）尚未实现，
 * 因此这里直接拒绝而不是「先跑起来再说」——工具一旦能发任意请求，
 * 模型生成的参数就可能打到内网。</p>
 */
@Slf4j
@Component
public class AgentToolExecutor {

    static final int MAX_RESULT_CHARS = 2000;

    @Autowired
    KbRagHolder kbRagHolder;

    private final ObjectMapper mapper = new ObjectMapper();

    public String execute(String uid, AgentToolBean tool, String arguments) throws AgentCallException {
        if (tool == null || tool.getToolType() == null) {
            throw new AgentCallException("工具未注册或缺少类型");
        }
        String type = tool.getToolType();
        if ("kb_search".equals(type)) {
            return searchKnowledge(uid, arguments);
        }
        if ("builtin".equals(type)) {
            return "当前时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        }
        if ("http".equals(type)) {
            throw new AgentCallException("HTTP 工具尚未配置目标地址白名单，暂不可用");
        }
        throw new AgentCallException("不支持的工具类型：" + type);
    }

    private String searchKnowledge(String uid, String arguments) throws AgentCallException {
        String query = readArg(arguments, "query");
        if (query == null || query.trim().isEmpty()) {
            throw new AgentCallException("知识库检索缺少 query 参数");
        }
        Result<KbSearchResult> result = kbRagHolder.search(uid, query.trim(), null, 3);
        if (result == null || result.getDatas() == null) {
            return "知识库检索无结果";
        }
        KbSearchResult data = result.getDatas();
        List<KbSearchItem> items = data.getItems();
        if (items == null || items.isEmpty()) {
            return data.getWarning() == null ? "知识库检索无结果" : data.getWarning();
        }
        StringBuilder sb = new StringBuilder();
        for (KbSearchItem item : items) {
            if (sb.length() > 0) {
                sb.append("\n---\n");
            }
            sb.append("[").append(item.getDocName()).append("] ").append(item.getContent());
            if (sb.length() >= MAX_RESULT_CHARS) {
                break;
            }
        }
        String text = sb.toString();
        return text.length() > MAX_RESULT_CHARS ? text.substring(0, MAX_RESULT_CHARS) : text;
    }

    /** 模型给的参数是 JSON 字符串，读取失败按缺参数处理，不把原始异常抛给模型 */
    private String readArg(String arguments, String field) {
        if (arguments == null || arguments.trim().isEmpty()) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(arguments);
            return root.path(field).asText(null);
        } catch (Exception e) {
            log.warn("[Agent] 工具参数不是合法 JSON: {}", e.getMessage());
            return null;
        }
    }
}
