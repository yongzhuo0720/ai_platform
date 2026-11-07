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

import javax.annotation.PreDestroy;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 工具执行器。
 *
 * <p>本轮只开放两类安全的内置工具：知识库检索（复用 {@link KbRagHolder#search}）
 * 与当前时间。HTTP 工具的目标地址白名单（SSRF 防护）尚未实现，
 * 因此这里直接拒绝而不是「先跑起来再说」——工具一旦能发任意请求，
 * 模型生成的参数就可能打到内网。</p>
 *
 * <p>工具在独立线程里执行并受 {@code toolTimeoutMs} 约束：检索依赖的向量库
 * 或上游一旦卡住，不设超时会把整条 Agent 链路一起拖死，而运行总超时只在
 * 每轮开始时检查，管不到单次工具调用。超时后 {@code cancel(true)} 只是尽力
 * 中断，阻塞在 socket 读上的线程仍要等自己的读超时才释放，所以线程数有上限。</p>
 */
@Slf4j
@Component
public class AgentToolExecutor {

    static final int MAX_RESULT_CHARS = 2000;
    static final long DEFAULT_TIMEOUT_MS = 8000L;
    static final long MAX_TIMEOUT_MS = 120000L;
    private static final int MAX_CONCURRENT_TOOLS = 16;

    @Autowired
    KbRagHolder kbRagHolder;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 无队列：16 个工具同时卡住时直接拒绝新调用，而不是无限排队把线程也占满 */
    private final ExecutorService toolPool = new ThreadPoolExecutor(
            MAX_CONCURRENT_TOOLS, MAX_CONCURRENT_TOOLS, 60L, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(), new ToolThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());

    @PreDestroy
    void shutdown() {
        toolPool.shutdownNow();
    }

    public String execute(String uid, AgentToolBean tool, String arguments) throws AgentCallException {
        return execute(uid, tool, arguments, null);
    }

    /**
     * 带超时地执行工具。{@code timeoutMs} 为空或非正数时用默认值，
     * 超过上限则按上限截断，避免配置错误把单次运行拖过总超时。
     */
    public String execute(String uid, AgentToolBean tool, String arguments, Long timeoutMs)
            throws AgentCallException {
        long timeout = timeoutMs == null || timeoutMs <= 0
                ? DEFAULT_TIMEOUT_MS : Math.min(timeoutMs, MAX_TIMEOUT_MS);
        Future<String> future;
        try {
            future = toolPool.submit(() -> runTool(uid, tool, arguments));
        } catch (RejectedExecutionException e) {
            throw new AgentCallException("工具并发已满，请稍后重试");
        }
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AgentCallException("工具执行超时（超过 " + timeout + " 毫秒）");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AgentCallException("工具执行被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AgentCallException) {
                throw (AgentCallException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new AgentCallException("工具执行失败，请稍后重试", cause);
        }
    }

    private String runTool(String uid, AgentToolBean tool, String arguments) throws AgentCallException {
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

    /** 守护线程：应用关闭时不必等待卡在上游读超时里的工具线程 */
    private static final class ToolThreadFactory implements ThreadFactory {
        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "agent-tool-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
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
