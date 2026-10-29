package com.aiproject.ai.permission.holde;

import com.aiproject.ai.permission.bean.MyRoleBean;
import com.aiproject.ai.permission.bean.MyUserBaseDb;
import com.aiproject.ai.permission.mapper.MyRoleBeanMapper;
import com.heneng.hnsp.business.account.model.SecurityConstants;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.db.utils.PageResult;
import com.heneng.hnsp.common.redis.service.RedisServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 此代码由[HSNP]快速开发框架自动生成.
 * [请在这里添加注释].
 *
 * @author heneng
 * @date 2026/05/11 14:21:48
 */
@Slf4j
@Service
public class MyRoleBeanHolder {
    /**
     * 链接的redis 时间
     */
    public static long urlTime = 60 * 60 * 25;
    /**
     * 数据库Mapper.
     */
    @Autowired
    MyRoleBeanMapper<MyRoleBean> roleBeanMapper = null;

    @Autowired
    RedisServer redisServer;

    @Autowired
    MyUserBaseDbHolder myUserBaseDbHolder;

    /**
     * 角色管理鉴权。
     *
     * 为什么必须校验：角色的 urlgroupids 就是「这个角色能访问哪些接口」，
     * 且会被 upRoleControlerGroup 写进 Redis 权限缓存。
     * 此前 create/remove/update 三个写接口都没有任何校验，
     * 任意登录用户可以修改任意角色（包括自己所属角色）的权限组 ——
     * 给自己角色加满权限组，效果等同于自我提权。
     *
     * 写法参照 ApplicationsBeanHolder.checkAdmin。
     *
     * @return null 表示校验通过；非 null 表示校验失败，可直接作为返回值
     */
    private Result<Boolean> checkAdmin(String uid) {
        if (uid == null) {
            return Result.failed(false, "请先登录");
        }
        if (!myUserBaseDbHolder.isAdmin(uid)) {
            return Result.failed(false, "仅管理员可维护角色");
        }
        return null;
    }

    /**
     * 创建
     *
     * @param uid
     * @param bean
     * @return
     */

    public Result<Boolean> create(String uid, List<MyRoleBean> bean) {
        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }
        for (int i = 0; i < bean.size(); i++) {
            bean.get(i).setLastuser(uid);
            upRoleControlerGroup(bean.get(i));
        }

        int i = roleBeanMapper.install(bean);
        // 原来是 i < 0，install 返回 0（一行都没插入）时也会被当成成功
        if (i <= 0) {
            return Result.failed("操作失败");
        }
        return Result.succeed(true, "操作成功, 插入数量:" + i);
    }

    /**
     * 删除
     *
     * @param bean
     * @return
     */

    /**
     * 删除角色。
     *
     * 注意签名新增了 uid：此前只有 bean，Controller 虽然拿到了 uid 却没传下来，
     * 导致这里无法做鉴权。
     */
    public Result<Boolean> remove(String uid, MyRoleBean bean) {
        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }
        if (roleBeanMapper.delete(bean) >= 1) {
            removeRoleControlerGroup(bean);
            return Result.succeed(true, "操作成功");
        }
        return Result.failed(false, "操作失败");
    }

    /**
     * 更新角色
     *
     * @param data
     * @return
     */

    public Result<Boolean> update(String uid, MyRoleBean data, MyRoleBean where) {
        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }
        data.setLastuser(uid);
        int res = roleBeanMapper.update(data, where);
        // 原来是 res < 0，更新 0 行时也会被当成成功
        if (res <= 0) {
            return Result.failed("操作失败");
        }
        // 更新权限组
        upRoleControlerGroup(data);
        return Result.succeed(true, "操作成功, 更新数量:" + res);
    }

    /**
     * 查询角色
     *
     * @param bean
     * @return
     */

    public Result<PageResult<MyRoleBean>> query(MyRoleBean bean, PageRequest pageRequest) {
        return roleBeanMapper.selectPageLike(pageRequest, bean);
    }

    /**
     * 按调用者身份查询角色（2026-10-29 新增）。
     *
     * <p><b>为什么不能直接要求管理员。</b> MainLayout（普通用户的主布局）会调这个接口，
     * 按自己的 rid 查 viewjson 来决定左侧菜单显示哪些项——这是正常业务。
     * 如果这里加 checkAdmin，所有普通用户的菜单会直接打不开。</p>
     *
     * <p><b>那原来的问题是什么。</b> 原实现 {@code query(bean, pageRequest)} 完全不校验身份：
     * 任意登录用户可以传任意 iid、或者干脆不传条件，把整张角色表读走，
     * 其中就包含 urlgroupids —— 也就是「这个角色能访问哪些接口」的完整清单。
     * 拿到它等于拿到平台的权限地图，配合其他接口可以更精准地找攻击面。</p>
     *
     * <p><b>修法。</b>管理员照旧查全部；非管理员强制把查询范围收敛到「自己所属的角色」：
     * 没传 iid 就替他填上自己的，传了别人的 iid 直接拒绝。</p>
     *
     * @param uid 调用者（来自 @LoginUserId，服务端注入，不信任前端传参）
     */
    public Result<PageResult<MyRoleBean>> queryForCaller(String uid, MyRoleBean bean, PageRequest pageRequest) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(null, "请先登录");
        }
        if (bean == null) {
            return Result.failed(null, "查询条件不能为空");
        }
        if (myUserBaseDbHolder.isAdmin(uid)) {
            return query(bean, pageRequest);
        }

        MyUserBaseDb self = myUserBaseDbHolder.queryUserByUid(uid);
        if (self == null || self.getRid() == null) {
            return Result.failed(null, "当前用户未分配角色");
        }
        Long ownRoleId = self.getRid();

        Long requested = bean.getIid();
        if (requested != null && !ownRoleId.equals(requested)) {
            return Result.failed(null, "只能查询自己所属的角色");
        }
        if (requested == null) {
            bean.setIid(ownRoleId);
        }
        return query(bean, pageRequest);
    }

    public List<MyRoleBean> queryList(MyRoleBean bean, PageRequest pageRequest) {
        return roleBeanMapper.select(pageRequest, bean);
    }

    /**
     * 更新角色权限组
     *
     * @param bean
     */

    public void upRoleControlerGroup(MyRoleBean bean) {
        String key = String.format("%s%d", SecurityConstants.userGroupKey, bean.getIid());
        // 先移除
        redisServer.delete(key);
        // 再添加
        if (bean.getUrlgroupids() != null) {
            redisServer.addSet(key, urlTime, bean.getUrlgroupids());
            log.debug("角色权限组:{}/{} => {}", key, bean.getName(), bean.getUrlgroupids());
        }
    }

    /**
     * 移除角色权限组
     *
     * @param bean
     */
    public void removeRoleControlerGroup(MyRoleBean bean) {
        String key = String.format("%s%d", SecurityConstants.userGroupKey, bean.getIid());
        // 移除
        redisServer.delete(key);
    }

}
