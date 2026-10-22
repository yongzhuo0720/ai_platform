package com.aiproject.ai.service.holde;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import blxt.qjava.utils.check.CheckUtils;
import com.aiproject.ai.permission.bean.MyUserBaseDb;
import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageResult;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.aiproject.ai.service.bean.KnowledgeBaseBean;
import com.aiproject.ai.service.mapper.KnowledgeBaseBeanMapper;

@Slf4j
@Service
public class KnowledgeBaseBeanHolder {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    MyUserBaseDbHolder myUserBaseDbHolder;

    @Autowired
    KnowledgeBaseBeanMapper<KnowledgeBaseBean> knowledgeBaseBeanMapper = null;

    @Autowired
    KnowledgeBaseMemberHolder knowledgeBaseMemberHolder;

    public Result<Boolean> install(String uid, KnowledgeBaseBean data) {
        if(CheckUtils.isEmpty(data.getRoleiids())){
            data.setRoleiids("[]");
        }
        if(CheckUtils.isEmpty(data.getUnitsnos())){
            data.setUnitsnos("[]");
        }
        // 创建时强制归属当前登录用户，避免伪造 ownerUid 抢占他人知识库归属
        data.setOwnerUid(uid);
        if (knowledgeBaseBeanMapper.install(data) <= 0) {
            return Result.failed(false, "增加失败");
        }
        return Result.succeed(true, "增加成功");
    }

    /**
     * 删除知识库权限记录。
     * <p>
     * 修复前：Controller 直接把请求体作为删除条件透传给 Mapper，不做任何归属校验，
     * 任意登录用户只要知道他人的 kbId / iid 即可删除其权限记录（越权删除）。
     */
    public Result<Boolean> delete(String uid, KnowledgeBaseBean data) {
        Result<Boolean> check = checkOwnership(uid, data);
        if (check != null) {
            return check;
        }
        if (knowledgeBaseBeanMapper.delete(data) <= 0) {
            return Result.failed(false, "删除失败");
        }
        return Result.succeed(true, "删除成功");
    }

    /**
     * 修改知识库权限记录。
     * <p>
     * 修复前：where 完全由调用方指定且不校验归属，
     * 任意登录用户可以改写他人知识库的 permissionType / 黑名单，从而把私有库放开或反向锁定。
     */
    public Result<Boolean> update(String uid, KnowledgeBaseBean data, KnowledgeBaseBean where) {
        Result<Boolean> check = checkOwnership(uid, where);
        if (check != null) {
            return check;
        }
        // 禁止普通用户通过 update 变更所有者，归属只能在 create 时确定
        if (!isAdmin(uid) && data.getOwnerUid() != null && !uid.equals(data.getOwnerUid())) {
            return Result.failed(false, "禁止变更知识库所有者");
        }
        data.setLastuser(uid);
        if (knowledgeBaseBeanMapper.update(data, where) <= 0) {
            return Result.failed(false, "修改失败");
        }
        return Result.succeed(true, "修改成功");
    }

    /**
     * 归属校验：用传入的条件查出目标记录，要求每一条都属于当前用户。
     * 超级管理员（rid=99999）放行。
     *
     * @return null 表示校验通过；非 null 表示校验失败，可直接作为返回值
     */
    private Result<Boolean> checkOwnership(String uid, KnowledgeBaseBean condition) {
        if (uid == null) {
            return Result.failed(false, "请先登录");
        }
        if (isAdmin(uid)) {
            return null;
        }
        List<KnowledgeBaseBean> targets = knowledgeBaseBeanMapper.select(null, condition);
        if (targets == null || targets.isEmpty()) {
            return Result.failed(false, "记录不存在");
        }
        for (KnowledgeBaseBean target : targets) {
            // 归属为空的脏数据（早期未写 ownerUid）不允许修改，交由管理员处理
            if (target.getOwnerUid() == null || !uid.equals(target.getOwnerUid())) {
                return Result.failed(false, "仅所有者可操作该知识库");
            }
        }
        return null;
    }

    /**
     * 判断是否为超级管理员。
     * 注意：用户不存在或 rid 为 null 时不能抛 NPE，按非管理员处理。
     */
    private boolean isAdmin(String uid) {
        MyUserBaseDb user = myUserBaseDbHolder.queryUserByUid(uid);
        return user != null && user.getRid() != null && user.getRid() == 99999L;
    }

    public KnowledgeBaseBean query(KnowledgeBaseBean data) {
        List<KnowledgeBaseBean> list = knowledgeBaseBeanMapper.select(null, data);
        if (list == null || list.size() == 0) {
            return null;
        }
        return list.get(0);
    }

    public List<KnowledgeBaseBean> queryList(KnowledgeBaseBean data, PageRequest pageRequest) {
        return knowledgeBaseBeanMapper.select(pageRequest, data);
    }

    public Result<PageResult<KnowledgeBaseBean>> queryPage(KnowledgeBaseBean data, PageRequest pageRequest) {
        return knowledgeBaseBeanMapper.selectPageLike(pageRequest, data);
    }

    public Result<List<KnowledgeBaseBean>> queryList(String uid, KnowledgeBaseBean data) {
        PageRequest pageRequest = new PageRequest();
        pageRequest.setOrder("iid");
        pageRequest.setDesc("desc");
        List<KnowledgeBaseBean> list = knowledgeBaseBeanMapper.select(pageRequest, data);
        return Result.succeed(list, "查询成功");
    }

    public Result<List<String>> filterVisible(String uid, String context, List<String> kbIds) {
        if (kbIds == null || kbIds.isEmpty()) {
            return Result.succeed(new ArrayList<>(), "查询成功");
        }

        MyUserBaseDb userBaseDb = myUserBaseDbHolder.queryUserByUid(uid);
        // 账号被删除 / rid 为空时不能直接 getRid()，此前这里是 NPE，接口直接 500
        boolean isAdmin = userBaseDb != null
                && userBaseDb.getRid() != null
                && userBaseDb.getRid() == 99999L;

        KnowledgeBaseBean queryBean = new KnowledgeBaseBean();
        List<KnowledgeBaseBean> allRecords = knowledgeBaseBeanMapper.select(null, queryBean);

        List<String> result = kbIds.stream().filter(kbId -> {
            KnowledgeBaseBean record = allRecords.stream()
                    .filter(r -> kbId.equals(r.getKbId()))
                    .findFirst()
                    .orElse(null);

            switch (context) {
                case "my":
                    return record != null && uid.equals(record.getOwnerUid());
                case "manage":
                    if (isAdmin) return true;
                    return record != null && uid.equals(record.getOwnerUid());
                case "square":
                    if (isAdmin) return true;
                    if (record == null) return false;
                    return isVisibleToUser(record, userBaseDb);
                case "shared":
                    if (record == null) return false;
                    return uid.equals(record.getOwnerUid())
                            && !"only_me".equals(record.getPermissionType());
                case "joined":
                    // 用户是成员 且 不是所有者
                    Set<String> joinedIds = knowledgeBaseMemberHolder.getJoinedKbIds(uid);
                    return joinedIds.contains(kbId)
                            && (record == null || !uid.equals(record.getOwnerUid()));
                default:
                    return false;
            }
        }).collect(Collectors.toList());

        return Result.succeed(result, "查询成功");
    }

    private boolean isVisibleToUser(KnowledgeBaseBean record, MyUserBaseDb userBaseDb) {
        String permissionType = record.getPermissionType();
        String ownerUid = record.getOwnerUid();

        if ("public".equals(permissionType)) {
            return true;
        }
        if ("only_me".equals(permissionType)) {
            return ownerUid != null && ownerUid.equals(userBaseDb.getUid());
        }
        if ("partial_invisible".equals(permissionType)) {
            try {
                List<String> unitsnos = objectMapper.readValue(
                        record.getUnitsnos() != null ? record.getUnitsnos() : "[]",
                        new TypeReference<List<String>>() {});
                List<Integer> roleiids = objectMapper.readValue(
                        record.getRoleiids() != null ? record.getRoleiids() : "[]",
                        new TypeReference<List<Integer>>() {});

                if (userBaseDb == null || userBaseDb.getRid() == null) {
                    log.warn("用户信息缺失，跳过 blacklist 校验, kbId={}", record.getKbId());
                    return false;
                }
                // rid 为空时 intValue() 会 NPE，需前置判空
                boolean roleBlocked = roleiids.contains(userBaseDb.getRid().intValue());
                boolean deptBlocked = unitsnos.contains(userBaseDb.getGid());

                return !roleBlocked && !deptBlocked;
            } catch (Exception e) {
                // 权限配置解析失败时按不可见处理（fail-closed）。
                // 原实现返回 true，等于「配错了就完全放开」，与安全预期相反。
                log.warn("解析权限配置失败，按不可见处理, kbId={}", record.getKbId(), e);
                return false;
            }
        }
        // 未知权限类型必须拒绝，避免配置错误导致资源意外公开。
        return false;
    }
}
