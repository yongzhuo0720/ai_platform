package com.aiproject.ai.service.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncManager;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.context.request.async.WebAsyncUtils;

import javax.servlet.http.HttpServletRequest;
import java.util.concurrent.Callable;

/**
 * 网关转发的线程模型。
 *
 * <p>此前网关直接在 Tomcat 请求线程里做上游 IO：一条流式回答能占住 Web 线程
 * 直到模型说完（默认上限 10 分钟），几十条长对话就能把登录、后台管理等
 * 无关键口一起拖慢。这里把整段转发交给 {@code aiGatewayExecutor}，
 * 请求线程在开始转发时立即归还；长流只占用数量可控的中继线程。</p>
 *
 * <p>整段转发（而不是只搬读循环）放在同一个线程里，是因为
 * {@code AiGatewayService} 用 ThreadLocal 串请求 ID 与首 token 时间；
 * 同线程执行保证这两项在审计落库时仍然有效。</p>
 *
 * <p>异步的代价是响应生命周期由容器掌握：超时或出错时 Spring 会先中断中继线程，
 * 再回收 response。这里为每个异步请求注册一个 {@link AsyncResponseGate}，
 * 容器结束请求前先关闭门闸，阻止中继线程继续写已经易主的 response。</p>
 *
 * <p>以下情况退回同步执行，保证功能不因部署形态而丢失：
 * 配置关闭、未注入线程池、过滤器链不支持 Servlet 异步。
 * 同步执行时行为与改造前完全一致，也没有容器级取消，门闸恒为放行。</p>
 */
@Slf4j
@Component
public class AiGatewayAsyncDispatcher {

    /** 与流式总时长护栏之间留出的收尾余量：结算、审计、关连接都需要时间 */
    private static final long ASYNC_TIMEOUT_SLACK_MS = 60_000L;

    @Autowired(required = false)
    @Qualifier("aiGatewayExecutor")
    private AsyncTaskExecutor executor;

    @Autowired
    AiGatewayProperties properties;

    /**
     * 提交一次网关转发。
     *
     * @param label 日志标识（模块名或端点名）
     * @param request 当前请求，用于判断容器是否支持异步
     * @param relay 实际转发逻辑；拿到的是本次请求的响应门闸
     * @return 需要返回给 Spring MVC 的异步任务；返回 {@code null}
     *         表示已在当前线程同步执行完毕，调用方直接返回即可
     */
    public WebAsyncTask<Void> dispatch(String label, HttpServletRequest request, AsyncRelay relay) {
        if (!properties.isAsyncDispatchEnabled()) {
            relay.run(AsyncResponseGate.NONE);
            return null;
        }
        if (executor == null) {
            log.warn("[AiGateway] 未找到 aiGatewayExecutor，{} 请求按同步方式转发", label);
            relay.run(AsyncResponseGate.NONE);
            return null;
        }
        if (!request.isAsyncSupported()) {
            // 过滤器链里只要有一个组件没声明 async-supported，startAsync 就会抛异常。
            // 这种情况不该让请求失败，退回原来的同步转发即可。
            log.warn("[AiGateway] 过滤器链不支持 Servlet 异步，{} 请求退回同步转发", label);
            relay.run(AsyncResponseGate.NONE);
            return null;
        }

        final long timeout = asyncTimeoutMs();
        final AsyncResponseGate gate = createGate(label, request, timeout);

        return new WebAsyncTask<Void>(timeout, executor, new Callable<Void>() {
            @Override
            public Void call() {
                try {
                    relay.run(gate);
                } catch (Throwable t) {
                    // proxy() 内部已兜住业务异常；能到这里的是 Error 或收尾异常。
                    // 此时响应多半已提交，抛回 Spring 只会再触发一次错误处理并污染日志。
                    log.error("[AiGateway] {} 异步转发异常退出", label, t);
                }
                return null;
            }
        });
    }

    /** 创建本次异步请求的门闸，并挂到容器的超时/错误回调上 */
    AsyncResponseGate createGate(String label, HttpServletRequest request, long timeout) {
        AsyncResponseGate gate = AsyncResponseGate.create();
        registerCancellationHook(label, request, gate, timeout);
        return gate;
    }

    /**
     * 在容器结束异步请求（超时/出错）之前关闭响应门闸。
     *
     * <p>必须用 {@link CallableProcessingInterceptor} 而不是
     * {@code WebAsyncTask.onTimeout/onError}：回调返回 {@code null} 会被当作
     * 并发结果，把超时错误吞成 200 空响应。返回 {@code RESULT_NONE} 则让
     * Spring 继续走原有的错误处理（超时兜底仍是 503）。</p>
     *
     * <p>Spring 的调用顺序是「先 cancel(true) 中断中继线程，再回调这里」。
     * {@link AsyncResponseGate#cancel()} 会与正在进行的那次响应写入互斥，
     * 因此它返回之后，容器再回收 response 就是安全的。</p>
     */
    private void registerCancellationHook(final String label, HttpServletRequest request,
                                          final AsyncResponseGate gate, final long timeout) {
        WebAsyncManager asyncManager = WebAsyncUtils.getAsyncManager(request);
        asyncManager.registerCallableInterceptor(gate, new CallableProcessingInterceptor() {
            @Override
            public <T> Object handleTimeout(NativeWebRequest request, Callable<T> task) {
                gate.cancel();
                log.warn("[AiGateway] {} 异步转发超过 {}ms 仍未结束，容器回收响应", label, timeout);
                return RESULT_NONE;
            }

            @Override
            public <T> Object handleError(NativeWebRequest request, Callable<T> task, Throwable t) {
                gate.cancel();
                log.info("[AiGateway] {} 异步请求被容器结束（客户端多半已断开），已停止响应写入: {}",
                        label, t.getMessage());
                return RESULT_NONE;
            }
        });
    }

    /**
     * 异步请求的超时时间。
     *
     * <p>必须大于「流式总时长 + 一次读超时 + 建连时间」：否则容器会先于
     * 网关自己的护栏结束请求，中继线程还在写一个已经关闭的响应。
     * 总时长未配置（不限流）时返回 0，表示不设容器级超时。</p>
     */
    long asyncTimeoutMs() {
        if (properties.getMaxStreamMs() <= 0) {
            return 0L;
        }
        return (long) properties.getMaxStreamMs()
                + Math.max(0, properties.getReadTimeoutMs())
                + Math.max(0, properties.getConnectTimeoutMs())
                + ASYNC_TIMEOUT_SLACK_MS;
    }

    /** 实际转发逻辑：与调度器放在一处，控制器不必关心门闸从哪来 */
    @FunctionalInterface
    public interface AsyncRelay {
        void run(AsyncResponseGate gate);
    }
}
