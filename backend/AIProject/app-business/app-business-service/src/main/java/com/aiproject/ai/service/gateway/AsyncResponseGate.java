package com.aiproject.ai.service.gateway;

import java.io.IOException;

/**
 * 异步响应的「容器回收」门闸。
 *
 * <p>Spring MVC 处理 Callable 超时/出错时，会先对中继任务
 * {@code Future.cancel(true)}，随后立刻让容器结束异步请求并回收
 * {@code HttpServletResponse}。中继线程收到中断后还要走 catch/finally
 * （写错误帧、刷新缓冲、审计、结算），此时手里的 response 可能已被下一个
 * 请求复用——继续写就会把字节送到别人的连接上，表现为「上一个请求断开后，
 * 下一个请求立刻收到 RST 或空响应」。</p>
 *
 * <p>容器线程在 {@code CallableProcessingInterceptor} 的 handleTimeout/handleError
 * 里调用 {@link #cancel()}。它与所有响应写入共用一把锁：cancel 返回后不会再有
 * 写入落到该 response 上，中继线程后续写入被 {@link #runIfActive(ResponseAction)}
 * 拒绝，容器因此可以安全回收。</p>
 */
public class AsyncResponseGate {

    /** 同步转发没有容器级取消，永远放行 */
    public static final AsyncResponseGate NONE = new AsyncResponseGate() {
        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean runIfActive(ResponseAction action) throws IOException {
            action.run();
            return true;
        }

        @Override
        public void onCancel(Runnable action) {
        }
    };

    private final Object lock = new Object();
    private boolean cancelled;
    private Runnable abortAction;

    protected AsyncResponseGate() {
    }

    public static AsyncResponseGate create() {
        return new AsyncResponseGate();
    }

    /**
     * 由容器线程在异步请求超时/出错时调用。返回后保证：正在进行的响应写入
     * 已经结束，且之后的写入都会被拒绝。
     */
    public void cancel() {
        Runnable abort;
        synchronized (lock) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            abort = abortAction;
            abortAction = null;
        }
        if (abort != null) {
            try {
                abort.run();
            } catch (Throwable ignored) {
                // 取消钩子失败不能阻断容器收尾
            }
        }
    }

    public boolean isCancelled() {
        synchronized (lock) {
            return cancelled;
        }
    }

    /** 注册取消时要执行的动作（网关用它关闭上游连接，唤醒阻塞中的读） */
    public void onCancel(Runnable action) {
        if (action == null) {
            return;
        }
        boolean runNow;
        synchronized (lock) {
            runNow = cancelled;
            if (!runNow) {
                abortAction = action;
            }
        }
        if (runNow) {
            try {
                action.run();
            } catch (Throwable ignored) {
                // 同上
            }
        }
    }

    /**
     * 仅在容器尚未回收 response 时执行一次写入。
     *
     * @return {@code false} 表示已经被取消，动作没有执行
     */
    public boolean runIfActive(ResponseAction action) throws IOException {
        synchronized (lock) {
            if (cancelled) {
                return false;
            }
            action.run();
            return true;
        }
    }

    public interface ResponseAction {
        void run() throws IOException;
    }
}