package com.aiproject.ai.service.gateway;

/**
 * 模型配置变更事件：后台新增 / 修改 / 删除 modelsconfigbean 成功后发布。
 *
 * <p><b>为什么用事件，而不是直接调用缓存持有方。</b>
 * {@link RerankResolver} 和 {@link ModelPriceResolver} 都依赖 ModelsConfigBeanHolder
 * 读取配置，各自持有 5 分钟 TTL 的进程内缓存。如果让 Holder 反过来注入它们，
 * 就形成了循环依赖（本项目虽开启 allow-circular-references，但不应主动制造环）；
 * 事件把「配置变了」与「谁需要刷新」解耦，将来新增缓存组件时只需监听事件，
 * 不用改写入路径。</p>
 *
 * <p><b>监听方约定：</b>只做缓存失效这类无副作用动作，且不得抛出异常——
 * 事件是同步广播，监听方抛异常会冒泡到已经成功的写操作（Holder 侧还有一层兜底）。</p>
 */
public class ModelConfigChangedEvent {
}
