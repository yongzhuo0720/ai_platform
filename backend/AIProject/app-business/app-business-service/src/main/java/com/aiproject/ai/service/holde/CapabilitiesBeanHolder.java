package com.aiproject.ai.service.holde;

import java.util.List;

import blxt.qjava.utils.check.CheckUtils;
import com.aiproject.ai.permission.bean.MyRoleBean;
import com.aiproject.ai.permission.bean.MyUserBaseDb;
import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageResult;
import com.heneng.hnsp.common.db.utils.PageRequest;

import java.lang.String;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.aiproject.ai.service.bean.CapabilitiesBean;
import com.aiproject.ai.service.mapper.CapabilitiesBeanMapper;
import com.aiproject.ai.service.util.ResourceVisibility;

/**
 * 此代码由[HSNP]快速开发框架自动生成.
 * [请在这里添加注释].
 *
 * @author heneng
 * @date 2026/05/13 14:53:02
 */
@Slf4j
@Service
public class CapabilitiesBeanHolder {

    @Autowired
    MyUserBaseDbHolder myUserBaseDbHolder;
    /**
     * 数据库Mapper.
     */
    @Autowired
    CapabilitiesBeanMapper<CapabilitiesBean> capabilitiesBeanMapper = null;

    /**
     * 能力广场是「平台级目录」，前端 CapabilitiesView 的增/改/删/权限配置按钮
     * 全部以 rid==99999 控制，即产品定义里只有超管能维护。
     * 后端此前没有任何校验，任意登录用户 POST /api/capabilitiesBean/delete
     * 就能清空整个能力广场 —— 典型的「仅前端鉴权」。
     */
    private Result<Boolean> checkAdmin(String uid) {
        if (uid == null) {
            return Result.failed(false, "请先登录");
        }
        if (!myUserBaseDbHolder.isAdmin(uid)) {
            return Result.failed(false, "仅管理员可维护能力广场");
        }
        return null;
    }

    /**
     * 功能描述: 增加.
     *
     * @param data 数据
     * @return Boolean
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/

    public Result<Boolean> install(String uid, CapabilitiesBean data) {
        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }
        return installInternal(data);
    }

    /**
     * 内部插入：不做管理员校验。
     * 仅供启动期数据注册器（ServiceDataRegister）等内部代码调用，不得暴露到 HTTP 接口。
     */
    public Result<Boolean> installInternal(CapabilitiesBean data) {
        if(CheckUtils.isEmpty(data.getRoleiids())){
            data.setRoleiids("[]");
        }
        if(CheckUtils.isEmpty(data.getUnitsnos())){
            data.setUnitsnos("[]");
        }
        if (capabilitiesBeanMapper.install(data) <= 0) {
            return Result.failed(false, "增加失败");
        }
        return Result.succeed(true, "增加成功");
    }

    /**
     * 功能描述: 删除.
     *
     * @param data 数据
     * @return Boolean
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/

    public Result<Boolean> delete(String uid, CapabilitiesBean data) {

        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }
        if (capabilitiesBeanMapper.delete(data) <= 0) {
            return Result.failed(false, "删除失败");
        }
        return Result.succeed(true, "删除成功");
    }

    /**
     * 功能描述: 修改.
     *
     * @param uid   用户id
     * @param data  数据
     * @param where 条件
     * @return Boolean
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/

    public Result<Boolean> update(String uid, CapabilitiesBean data, CapabilitiesBean where) {

        Result<Boolean> check = checkAdmin(uid);
        if (check != null) {
            return check;
        }
        data.setLastuser(uid);
        if (capabilitiesBeanMapper.update(data, where) <= 0) {
            return Result.failed(false, "修改失败");
        }
        return Result.succeed(true, "修改成功");
    }

    /**
     * 功能描述: 查询单个.
     *
     * @param data 数据
     * @return 对象
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/

    public CapabilitiesBean query(CapabilitiesBean data) {

        List<CapabilitiesBean> list = capabilitiesBeanMapper.select(null, data);
        if (list == null || list.size() == 0) {
            return null;
        }
        return list.get(0);
    }

    /**
     * 功能描述: 查询集合.
     *
     * @param data        数据
     * @param pageRequest 分页条件
     * @return List
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/

    public List<CapabilitiesBean> queryList(CapabilitiesBean data, PageRequest pageRequest) {

        return capabilitiesBeanMapper.select(pageRequest, data);
    }

    /**
     * 功能描述: 分页查询.
     *
     * @param data        数据
     * @param pageRequest 分页条件
     * @return Result
     * @author heneng
     * @date 2026/05/13 14:53:02
     **/

    public Result<PageResult<CapabilitiesBean>> queryPage(CapabilitiesBean data, PageRequest pageRequest) {

        return capabilitiesBeanMapper.selectPageLike(pageRequest, data);
    }

    public Result<List<CapabilitiesBean>> queryList(String uid, CapabilitiesBean data) {
        PageRequest pageRequest = new PageRequest();
        pageRequest.setOrder("iid");
        pageRequest.setDesc("desc");
        //查询用户信息
        MyUserBaseDb userBaseDb = myUserBaseDbHolder.queryUserByUid(uid);
        // 账号不存在 / rid 为空时不能直接 getRid()，此前这里是 NPE，接口直接 500。
        // 且此时无法构造角色/部门过滤条件，按「无可见数据」返回，避免 fail-open 泄露全部能力。
        if (userBaseDb == null || userBaseDb.getRid() == null) {
            log.warn("用户信息缺失，能力广场查询返回空列表, uid={}", uid);
            return Result.succeed(new java.util.ArrayList<CapabilitiesBean>(), "查询成功");
        }
        if (userBaseDb.getRid().equals(99999L)) {
            return Result.succeed(capabilitiesBeanMapper.select(pageRequest, data), "查询成功");
        }
        /*
         * 越权防护（SQL 注入）。
         * 修复前这里把 rid / gid 用 String.format 拼进原生 WHERE 片段：
         *   String sql = String.format("NOT (roleiids::jsonb @> '%s') and NOT (unitsnos::jsonb @> '\"%s\"')", ...)
         * 框架的 select(..., String...) 是把片段原样拼进 SQL 的、不做参数绑定，
         * 而 gid 属于用户表可写字段 → 引号闭合即可让过滤条件恒真，越权读取全部能力。
         * 现在改为在 Java 里做等价的 jsonb 包含判断（见 ResourceVisibility），
         * 既消除注入面，也避免非 JSON 历史数据触发 ::jsonb 的 22P02 报错。
         */
        List<CapabilitiesBean> all = capabilitiesBeanMapper.select(pageRequest, data);
        List<CapabilitiesBean> visible = new java.util.ArrayList<>();
        if (all != null) {
            for (CapabilitiesBean item : all) {
                if (ResourceVisibility.isVisible(item.getRoleiids(), item.getUnitsnos(), userBaseDb)) {
                    visible.add(item);
                }
            }
        }
        return Result.succeed(visible, "查询成功");
    }

}
