package com.aiproject.ai.permission.spi;

/**
 * 标签绑定的资源归属校验扩展点。
 * <p>
 * 标签绑定表（lablechildbean）的 type/hash 指向各业务模块的资源，但 permission
 * 模块不能反向依赖业务模块，因此把「这个资源是不是该用户的」判定下沉到这里，
 * 由拥有该资源的业务模块注册实现。
 * <p>
 * 超级管理员不经过本接口，直接放行；没有匹配实现时按「仅管理员可维护」处理。
 */
public interface LableBindingResourceGuard {

    /** 负责的资源类型，需与前端写入 lablechildbean.type 的值一致，如 knowledge_base */
    String type();

    /** 当前用户是否拥有（可管理）该资源 */
    boolean canManage(String uid, String hash);
}
