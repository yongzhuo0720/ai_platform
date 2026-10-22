package com.aiproject.ai.service.holde;

import com.aiproject.ai.permission.bean.MyUserBaseDb;
import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.aiproject.ai.service.bean.KnowledgeBaseBean;
import com.aiproject.ai.service.mapper.KnowledgeBaseBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * 知识库可见性判断的单元测试。
 *
 * 可见性逻辑（isVisibleToUser）是本项目权限模型的核心：
 *   public            所有人可见
 *   only_me           仅所有者
 *   partial_invisible 按部门 / 角色黑名单排除
 *
 * 这里通过 public 的 filterVisible(uid, "square", kbIds) 间接覆盖它，
 * 重点验证两类曾经出过问题的行为：
 *   1. 权限配置（roleiids / unitsnos）解析失败时必须按【不可见】处理（fail-closed），
 *      原实现返回 true，等于「配错了就完全放开」；
 *   2. 用户不存在或 rid 为空时不能抛 NPE（原实现直接 getRid() 导致接口 500）。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseBeanHolderTest {

    @Mock
    MyUserBaseDbHolder myUserBaseDbHolder;

    @Mock
    KnowledgeBaseBeanMapper<KnowledgeBaseBean> knowledgeBaseBeanMapper;

    @Mock
    KnowledgeBaseMemberHolder knowledgeBaseMemberHolder;

    @InjectMocks
    KnowledgeBaseBeanHolder holder;

    /** 构造一个用户；rid 为角色 id，gid 为部门 unitsno */
    private MyUserBaseDb user(String uid, Long rid, String gid) {
        MyUserBaseDb u = new MyUserBaseDb();
        u.setUid(uid);
        u.setRid(rid);
        u.setGid(gid);
        return u;
    }

    private KnowledgeBaseBean record(String kbId, String ownerUid, String permissionType,
                                     String roleiids, String unitsnos) {
        KnowledgeBaseBean r = new KnowledgeBaseBean();
        r.setKbId(kbId);
        r.setOwnerUid(ownerUid);
        r.setPermissionType(permissionType);
        r.setRoleiids(roleiids);
        r.setUnitsnos(unitsnos);
        return r;
    }

    /** 走 square 场景，返回该 kbId 是否对当前用户可见 */
    private boolean visibleInSquare(String uid, MyUserBaseDb userBaseDb, KnowledgeBaseBean rec) {
        when(myUserBaseDbHolder.queryUserByUid(uid)).thenReturn(userBaseDb);
        when(knowledgeBaseBeanMapper.select(isNull(), any(KnowledgeBaseBean.class)))
                .thenReturn(Collections.singletonList(rec));
        Result<List<String>> result =
                holder.filterVisible(uid, "square", Collections.singletonList(rec.getKbId()));
        return result.getDatas() != null && result.getDatas().contains(rec.getKbId());
    }

    @Test
    void public_shouldBeVisibleToAnyone() {
        KnowledgeBaseBean rec = record("kb1", "owner1", "public", "[]", "[]");
        assertTrue(visibleInSquare("u2", user("u2", 5L, "dept01"), rec));
    }

    @Test
    void onlyMe_shouldBeVisibleToOwner() {
        KnowledgeBaseBean rec = record("kb1", "u1", "only_me", "[]", "[]");
        assertTrue(visibleInSquare("u1", user("u1", 5L, "dept01"), rec));
    }

    @Test
    void onlyMe_shouldBeHiddenFromOthers() {
        KnowledgeBaseBean rec = record("kb1", "u1", "only_me", "[]", "[]");
        assertFalse(visibleInSquare("u2", user("u2", 5L, "dept01"), rec));
    }

    @Test
    void partialInvisible_shouldBeVisible_whenNotInBlacklist() {
        KnowledgeBaseBean rec = record("kb1", "owner1", "partial_invisible", "[99]", "[\"dept99\"]");
        assertTrue(visibleInSquare("u1", user("u1", 5L, "dept01"), rec));
    }

    @Test
    void partialInvisible_shouldBeHidden_whenRoleBlocked() {
        // 用户角色 rid=5 命中角色黑名单
        KnowledgeBaseBean rec = record("kb1", "owner1", "partial_invisible", "[5]", "[]");
        assertFalse(visibleInSquare("u1", user("u1", 5L, "dept01"), rec));
    }

    @Test
    void partialInvisible_shouldBeHidden_whenDeptBlocked() {
        // 用户部门 dept01 命中部门黑名单
        KnowledgeBaseBean rec = record("kb1", "owner1", "partial_invisible", "[]", "[\"dept01\"]");
        assertFalse(visibleInSquare("u1", user("u1", 5L, "dept01"), rec));
    }

    /** 权限配置是坏 JSON：必须按不可见处理（fail-closed），不能因为解析失败而放开 */
    @Test
    void partialInvisible_shouldBeHidden_whenConfigIsBroken() {
        KnowledgeBaseBean rec = record("kb1", "owner1", "partial_invisible", "[not-json", "[]");
        assertFalse(visibleInSquare("u1", user("u1", 5L, "dept01"), rec));
    }

    /** 未知权限类型：拒绝，避免配置错误导致资源意外公开 */
    @Test
    void unknownPermissionType_shouldBeHidden() {
        KnowledgeBaseBean rec = record("kb1", "owner1", "some_unknown_type", "[]", "[]");
        assertFalse(visibleInSquare("u1", user("u1", 5L, "dept01"), rec));
    }

    /** 超管在广场场景可以看全部 */
    @Test
    void square_shouldBeVisibleToAdmin() {
        KnowledgeBaseBean rec = record("kb1", "owner1", "only_me", "[]", "[]");
        assertTrue(visibleInSquare("admin", user("admin", 99999L, "dept00"), rec));
    }

    /** 账号不存在（userBaseDb 为 null）：不能抛 NPE，按不可见处理 */
    @Test
    void partialInvisible_shouldBeHidden_whenUserMissing() {
        KnowledgeBaseBean rec = record("kb1", "owner1", "partial_invisible", "[]", "[]");
        assertFalse(visibleInSquare("u1", null, rec));
    }

    /** 用户 rid 为空：不能抛 NPE，按不可见处理 */
    @Test
    void partialInvisible_shouldBeHidden_whenRidIsNull() {
        KnowledgeBaseBean rec = record("kb1", "owner1", "partial_invisible", "[]", "[]");
        assertFalse(visibleInSquare("u1", user("u1", null, "dept01"), rec));
    }
}
