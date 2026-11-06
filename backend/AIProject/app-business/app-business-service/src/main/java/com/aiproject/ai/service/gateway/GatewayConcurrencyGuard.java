package com.aiproject.ai.service.gateway;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * 网关并发闸门：限制「每个模块同时进行中的上游请求数」。
 * <p>
 * 配额与限流管的是「谁可以发」，管不住「同时有多少个请求正占着 Web 线程
 * 等上游」。上游一旦变慢，请求会在线程池里越堆越多，最后连登录这类
 * 无关接口都拿不到线程。这里在调用上游之前做一次非阻塞的许可检查：
 * 拿不到就立刻 429，不排队、不占用线程、也不产生上游费用。
 * <p>
 * 按模块分别计数，避免长对话把文件上传/知识库检索一起饿死。
 * 许可在请求进入 {@code doProxy} 后获取、在 finally 中释放，
 * 覆盖「读上游 + 写浏览器」的完整生命周期。
 */
@Component
public class GatewayConcurrencyGuard {

    /** 超限错误码：前端可据此提示「忙，稍后重试」，区别于额度用尽的 quota_exceeded */
    public static final String CODE_BUSY = "gateway_busy";

    private static final String DEFAULT_MODULE = "default";

    private final AiGatewayProperties properties;

    /**
     * 每个模块一个信号量。数量在首次使用时按配置创建；
     * 修改配置需要重启才生效（运行期热更新这里没有做，避免许可数中途变化时出现负数）。
     */
    private final ConcurrentHashMap<String, Semaphore> semaphores =
            new ConcurrentHashMap<String, Semaphore>();

    public GatewayConcurrencyGuard(AiGatewayProperties properties) {
        this.properties = properties;
    }

    /**
     * 尝试占用一个并发许可。
     *
     * @return 许可对象；超限返回 {@code null}。未限制并发时返回一个无需释放的空许可
     */
    public Lease tryAcquire(String module) {
        int limit = properties.getMaxConcurrentPerModule();
        if (limit <= 0) {
            return Lease.UNLIMITED;
        }
        Semaphore semaphore = semaphoreFor(module, limit);
        return semaphore.tryAcquire() ? new Lease(semaphore) : null;
    }

    private Semaphore semaphoreFor(String module, int limit) {
        String key = module == null ? DEFAULT_MODULE : module;
        Semaphore existing = semaphores.get(key);
        if (existing != null) {
            return existing;
        }
        return semaphores.computeIfAbsent(key, k -> new Semaphore(limit));
    }

    /** 许可句柄：release 必须只调一次，重复释放会把并发上限悄悄放大 */
    public static final class Lease {
        private static final Lease UNLIMITED = new Lease(null);

        private final Semaphore semaphore;

        private Lease(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        public void release() {
            if (semaphore != null) {
                semaphore.release();
            }
        }
    }
}
