package com.aiproject.ai.service.holde;

import java.util.ArrayList;
import java.util.List;

import com.heneng.hnsp.common.croe.model.Result;
import com.aiproject.ai.permission.bean.MyUserBaseDb;
import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.heneng.hnsp.common.db.utils.PageResult;
import com.heneng.hnsp.common.db.utils.PageRequest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.aiproject.ai.service.bean.AssistantBean;
import com.aiproject.ai.service.mapper.AssistantBeanMapper;
import com.aiproject.ai.service.util.ResourceVisibility;

@Slf4j
@Service
public class AssistantBeanHolder {

    @Autowired
    AssistantBeanMapper<AssistantBean> assistantBeanMapper = null;

    @Autowired
    MyUserBaseDbHolder myUserBaseDbHolder;

    public Result<AssistantBean> install(AssistantBean data) {
        // 兜底：权限字段为空时给默认值，避免 jsonb 过滤时空值异常（与能力广场一致）
        if (data.getPermissionType() == null || data.getPermissionType().isEmpty()) {
            data.setPermissionType("public");
        }
        if (data.getRoleiids() == null || data.getRoleiids().isEmpty()) {
            data.setRoleiids("[]");
        }
        if (data.getUnitsnos() == null || data.getUnitsnos().isEmpty()) {
            data.setUnitsnos("[]");
        }
        if (assistantBeanMapper.install(data) <= 0) {
            return Result.failed(null, "增加失败");
        }
        return Result.succeed(data, "增加成功");
    }

    public Result<Boolean> delete(String uid, AssistantBean data) {
        data.setUserId(uid);
        if (assistantBeanMapper.delete(data) <= 0) {
            return Result.failed(false, "删除失败");
        }
        return Result.succeed(true, "删除成功");
    }

    /**
     * 修改助手。
     *
     * <p>归属收敛：{@code data.userId} 会被拼进 UPDATE 的 SET 子句，
     * 修复前没有强制为当前登录 uid，所有者可以把助手转让给任意 uid（或抢走他人助手）。
     * 现在强制覆盖，归属只能在 create 时确定。</p>
     */
    public Result<Boolean> update(String uid, AssistantBean data, AssistantBean where) {
        data.setUserId(uid);
        data.setLastuser(uid);
        where.setUserId(uid);
        if (assistantBeanMapper.update(data, where) <= 0) {
            return Result.failed(false, "修改失败");
        }
        return Result.succeed(true, "修改成功");
    }

    public Result<List<AssistantBean>> queryList(String uid) {
        MyUserBaseDb userBaseDb = myUserBaseDbHolder.queryUserByUid(uid);
        List<AssistantBean> list = new ArrayList<AssistantBean>();
        PageRequest pageRequest = new PageRequest();
        pageRequest.setOrder("iid");
        pageRequest.setDesc("desc");

        /*
         * 越权防护（SQL 注入 + 列名歧义）。
         * 修复前这里是：
         *   String adminSql = "select  * from assistantbean assistantbean_t where assistantbean_t.\"userId\" in (select uid from userbase where rid=99999 )";
         *   adminSql += String.format(" and NOT (COALESCE(roleiids,'[]')::jsonb @> '%s') and NOT (... '%s')", rid, gid);
         *   assistantBeanMapper.selectBySql(adminSql);
         * 两个问题：
         *  1) rid / gid 直接拼进 SQL，不绑定参数 → SQL 注入；
         *  2) `select *` 读的是物理列顺序，而 assistantbean 表里历史遗留了
         *     大小写重复列（TableSchemaInitializer 曾用未加引号的 DDL 建列，
         *     PostgreSQL 折叠成小写），MyBatis 自动映射大小写不敏感，
         *     可能把值映射到错误的列上。
         * 现在改为走框架标准 select（显式列出带引号的驼峰列名），权限过滤放到 Java 端。
         */
        List<AssistantBean> all = assistantBeanMapper.select(pageRequest, new AssistantBean());
        if (all != null) {
            for (AssistantBean item : all) {
                // 超管创建的助手对所有登录用户可见；自己的助手不受黑名单约束
                boolean fromAdmin = isCreatedByAdmin(item);
                boolean isOwn = uid != null && uid.equals(item.getUserId());

                boolean visible;
                if (fromAdmin || isOwn) {
                    visible = true;
                } else if (userBaseDb == null || userBaseDb.getRid() == null) {
                    visible = false;
                } else {
                    visible = ResourceVisibility.isVisible(item.getRoleiids(), item.getUnitsnos(), userBaseDb);
                }
                if (!visible) {
                    continue;
                }
                list.add(item);
            }
        }
        return Result.succeed(list, "查询成功");
    }

    /** 判断助手是否为超级管理员（rid=99999）创建 */
    private boolean isCreatedByAdmin(AssistantBean item) {
        if (item == null || item.getUserId() == null) {
            return false;
        }
        MyUserBaseDb creator = myUserBaseDbHolder.queryUserByUid(item.getUserId());
        return creator != null && creator.getRid() != null && creator.getRid().equals(99999L);
    }
}
