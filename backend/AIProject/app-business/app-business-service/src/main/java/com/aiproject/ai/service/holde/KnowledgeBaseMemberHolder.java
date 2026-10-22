package com.aiproject.ai.service.holde;

import java.util.*;

import com.aiproject.ai.permission.bean.MyUserBaseDb;
import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.aiproject.ai.service.bean.KnowledgeBaseBean;
import com.aiproject.ai.service.bean.KnowledgeBaseMemberBean;
import com.aiproject.ai.service.mapper.KnowledgeBaseBeanMapper;
import com.aiproject.ai.service.mapper.KnowledgeBaseMemberMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
@Service
public class KnowledgeBaseMemberHolder {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    KnowledgeBaseMemberMapper<KnowledgeBaseMemberBean> knowledgeBaseMemberMapper = null;

    @Autowired
    KnowledgeBaseBeanMapper<KnowledgeBaseBean> knowledgeBaseBeanMapper = null;

    @Autowired
    MyUserBaseDbHolder myUserBaseDbHolder;

    public Result<Boolean> join(String kbId, String uid) {
        // 检查是否是自己的知识库
        KnowledgeBaseBean kbQuery = new KnowledgeBaseBean();
        kbQuery.setKbId(kbId);
        List<KnowledgeBaseBean> kbRecords = knowledgeBaseBeanMapper.select(null, kbQuery);
        if (kbRecords == null || kbRecords.isEmpty()) {
            return Result.failed(false, "知识库不存在");
        }
        KnowledgeBaseBean record = kbRecords.get(0);
        if (uid.equals(record.getOwnerUid())) {
            return Result.failed(false, "不能加入自己创建的知识库");
        }
        if (!isJoinable(record, uid)) {
            return Result.failed(false, "当前知识库不允许加入");
        }

        KnowledgeBaseMemberBean existing = new KnowledgeBaseMemberBean();
        existing.setKbId(kbId);
        existing.setUserId(uid);
        List<KnowledgeBaseMemberBean> list = knowledgeBaseMemberMapper.select(null, existing);
        if (list != null && !list.isEmpty()) {
            return Result.succeed(true, "已加入");
        }
        KnowledgeBaseMemberBean bean = new KnowledgeBaseMemberBean();
        bean.setKbId(kbId);
        bean.setUserId(uid);
        bean.setLastuser(uid);
        if (knowledgeBaseMemberMapper.install(bean) <= 0) {
            return Result.failed(false, "加入失败");
        }
        return Result.succeed(true, "加入成功");
    }

    public Result<Boolean> leave(String kbId, String uid) {
        KnowledgeBaseMemberBean bean = new KnowledgeBaseMemberBean();
        bean.setKbId(kbId);
        bean.setUserId(uid);
        if (knowledgeBaseMemberMapper.delete(bean) <= 0) {
            return Result.failed(false, "未加入该知识库");
        }
        return Result.succeed(true, "退出成功");
    }

    public Result<Boolean> removeMember(String kbId, String targetUserId, String ownerUid) {
        KnowledgeBaseBean queryBean = new KnowledgeBaseBean();
        queryBean.setKbId(kbId);
        List<KnowledgeBaseBean> records = knowledgeBaseBeanMapper.select(null, queryBean);
        if (records == null || records.isEmpty()) {
            return Result.failed(false, "知识库不存在");
        }
        KnowledgeBaseBean record = records.get(0);
        if (!ownerUid.equals(record.getOwnerUid())) {
            return Result.failed(false, "仅所有者可移除成员");
        }
        KnowledgeBaseMemberBean bean = new KnowledgeBaseMemberBean();
        bean.setKbId(kbId);
        bean.setUserId(targetUserId);
        if (knowledgeBaseMemberMapper.delete(bean) <= 0) {
            return Result.failed(false, "该用户不是成员");
        }
        return Result.succeed(true, "移除成功");
    }

    public Result<List<Map<String, String>>> getMembers(String kbId, String uid) {
        KnowledgeBaseMemberBean queryBean = new KnowledgeBaseMemberBean();
        queryBean.setKbId(kbId);
        List<KnowledgeBaseMemberBean> list = knowledgeBaseMemberMapper.select(null, queryBean);

        // 查所有者
        KnowledgeBaseBean kbQuery = new KnowledgeBaseBean();
        kbQuery.setKbId(kbId);
        List<KnowledgeBaseBean> kbRecords = knowledgeBaseBeanMapper.select(null, kbQuery);
        String ownerUid = (kbRecords != null && !kbRecords.isEmpty()) ? kbRecords.get(0).getOwnerUid() : null;
        if (ownerUid == null || (!uid.equals(ownerUid) && !myUserBaseDbHolder.isAdmin(uid))) {
            return Result.failed(new ArrayList<>(), "仅所有者或管理员可查看成员");
        }

        List<Map<String, String>> result = new ArrayList<>();
        if (list != null) {
            for (KnowledgeBaseMemberBean m : list) {
                Map<String, String> member = new HashMap<>();
                member.put("userId", m.getUserId());
                member.put("userName", getUserName(m.getUserId()));
                member.put("isOwner", m.getUserId().equals(ownerUid) ? "true" : "false");
                result.add(member);
            }
        }
        return Result.succeed(result, "查询成功");
    }

    public Set<String> getJoinedKbIds(String uid) {
        KnowledgeBaseMemberBean queryBean = new KnowledgeBaseMemberBean();
        queryBean.setUserId(uid);
        List<KnowledgeBaseMemberBean> list = knowledgeBaseMemberMapper.select(null, queryBean);
        Set<String> ids = new HashSet<>();
        if (list != null) {
            for (KnowledgeBaseMemberBean m : list) {
                ids.add(m.getKbId());
            }
        }
        return ids;
    }

    public boolean isMember(String kbId, String uid) {
        KnowledgeBaseMemberBean queryBean = new KnowledgeBaseMemberBean();
        queryBean.setKbId(kbId);
        queryBean.setUserId(uid);
        List<KnowledgeBaseMemberBean> list = knowledgeBaseMemberMapper.select(null, queryBean);
        return list != null && !list.isEmpty();
    }

    private String getUserName(String uid) {
        try {
            MyUserBaseDb user = myUserBaseDbHolder.queryUserByUid(uid);
            return user != null ? user.getUname() : uid;
        } catch (Exception e) {
            return uid;
        }
    }

    private boolean isJoinable(KnowledgeBaseBean record, String uid) {
        if (myUserBaseDbHolder.isAdmin(uid)) {
            return true;
        }
        if ("public".equals(record.getPermissionType())) {
            return true;
        }
        if (!"partial_invisible".equals(record.getPermissionType())) {
            return false;
        }
        MyUserBaseDb user = myUserBaseDbHolder.queryUserByUid(uid);
        if (user == null || user.getRid() == null) {
            return false;
        }
        try {
            List<String> units = OBJECT_MAPPER.readValue(record.getUnitsnos() == null ? "[]" : record.getUnitsnos(), new TypeReference<List<String>>() {});
            List<Integer> roles = OBJECT_MAPPER.readValue(record.getRoleiids() == null ? "[]" : record.getRoleiids(), new TypeReference<List<Integer>>() {});
            return !units.contains(user.getGid()) && !roles.contains(user.getRid().intValue());
        } catch (Exception e) {
            log.warn("解析知识库加入权限失败，按拒绝处理, kbId={}", record.getKbId(), e);
            return false;
        }
    }
}
