package com.aiproject.ai.service.holde;

import com.aiproject.ai.permission.bean.MyUserBaseDb;
import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.aiproject.ai.service.bean.KbChunkBean;
import com.aiproject.ai.service.bean.KbDocBean;
import com.aiproject.ai.service.bean.KbDocContentBean;
import com.aiproject.ai.service.bean.KnowledgeBaseBean;
import com.aiproject.ai.service.mapper.KbChunkBeanMapper;
import com.aiproject.ai.service.mapper.KbDocBeanMapper;
import com.aiproject.ai.service.mapper.KbDocContentBeanMapper;
import com.aiproject.ai.service.mapper.KnowledgeBaseBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Mock
    KbDocBeanMapper<KbDocBean> kbDocBeanMapper;

    @Mock
    KbChunkBeanMapper<KbChunkBean> kbChunkBeanMapper;

    @Mock
    KbDocContentBeanMapper<KbDocContentBean> kbDocContentBeanMapper;

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

    /**
     * only_me + 用户不存在：不能抛 NPE。
     * 修复前 isVisibleToUser 的 only_me 分支直接 ownerUid.equals(userBaseDb.getUid())，
     * square 场景下 userBaseDb 可能为 null，一进这个分支就 NPE 500。
     */
    @Test
    void onlyMe_shouldBeHidden_whenUserMissing() {
        KnowledgeBaseBean rec = record("kb1", "u1", "only_me", "[]", "[]");
        assertFalse(visibleInSquare("ghost", null, rec));
    }

    /**
     * queryList 归属收窄：非管理员看不到他人的 only_me 记录。
     *
     * 修复前 queryList(uid, data) 把 uid 声明了却完全不用，
     * 任意登录用户可拉全量知识库权限记录（含他人的 ownerUid / kbId / 黑名单）。
     *
     * 注意不断言「只剩自己的」——public 与 partial_invisible 的记录
     * 其存在本身就对他人公开，前端要靠它们算「广场」「已加入」标签页。
     */
    @Test
    void queryList_shouldHideOthersOnlyMeRecords() {
        KnowledgeBaseBean own = record("kb-own", "u1", "only_me", "[]", "[]");
        KnowledgeBaseBean othersPrivate = record("kb-private", "u2", "only_me", "[]", "[]");
        KnowledgeBaseBean othersPublic = record("kb-public", "u2", "public", "[]", "[]");

        when(myUserBaseDbHolder.queryUserByUid("u1")).thenReturn(user("u1", 5L, "dept01"));
        when(knowledgeBaseBeanMapper.select(any(PageRequest.class), any(KnowledgeBaseBean.class)))
                .thenReturn(new ArrayList<KnowledgeBaseBean>(Arrays.asList(own, othersPrivate, othersPublic)));

        Result<List<KnowledgeBaseBean>> result =
                holder.queryList("u1", new KnowledgeBaseBean());
        List<KnowledgeBaseBean> list = result.getDatas();

        assertEquals(2, list.size(), "自己的 + 他人 public 的应保留");
        assertTrue(list.contains(own));
        assertTrue(list.contains(othersPublic));
        assertFalse(list.contains(othersPrivate), "他人的 only_me 必须隐藏");
    }

    /** 超管不受收窄影响，仍可拿全量记录做管理 */
    @Test
    void queryList_shouldReturnAllForAdmin() {
        KnowledgeBaseBean othersPrivate = record("kb-private", "u2", "only_me", "[]", "[]");
        when(myUserBaseDbHolder.queryUserByUid("admin")).thenReturn(user("admin", 99999L, "dept00"));
        when(knowledgeBaseBeanMapper.select(any(PageRequest.class), any(KnowledgeBaseBean.class)))
                .thenReturn(new ArrayList<KnowledgeBaseBean>(Arrays.asList(othersPrivate)));

        Result<List<KnowledgeBaseBean>> result = holder.queryList("admin", new KnowledgeBaseBean());
        assertEquals(1, result.getDatas().size());
    }

    /**
     * 删除知识库必须级联清理本地 RAG。
     * 修复前只删 knowledgebasebean，kbdocbean / kbchunkbean 原样留下：
     * 数据库里看不到这些文档的入口，但它们仍参与检索，属于「删不掉的残留数据」。
     */
    @Test
    void delete_shouldCascadeLocalDocumentsByOwner() {
        KnowledgeBaseBean target = record("kb-1", "u1", "public", "[]", "[]");
        target.setIid(1L);
        target.setName("研发知识库");
        when(myUserBaseDbHolder.queryUserByUid("u1")).thenReturn(user("u1", 5L, "dept01"));
        when(knowledgeBaseBeanMapper.select(isNull(), any(KnowledgeBaseBean.class)))
                .thenReturn(Collections.singletonList(target));
        when(kbChunkBeanMapper.delete(any(KbChunkBean.class))).thenReturn(3);
        when(kbDocContentBeanMapper.delete(any(KbDocContentBean.class))).thenReturn(2);
        when(kbDocBeanMapper.delete(any(KbDocBean.class))).thenReturn(2);
        when(knowledgeBaseBeanMapper.delete(any(KnowledgeBaseBean.class))).thenReturn(1);

        KnowledgeBaseBean request = new KnowledgeBaseBean();
        request.setKbId("kb-1");
        Result<Boolean> result = holder.delete("u1", request);

        assertTrue(Boolean.TRUE.equals(result.getDatas()));
        assertTrue(result.getResp_msg().contains("2 篇本地文档"));

        ArgumentCaptor<KbChunkBean> chunkCaptor = ArgumentCaptor.forClass(KbChunkBean.class);
        verify(kbChunkBeanMapper).delete(chunkCaptor.capture());
        assertEquals("研发知识库", chunkCaptor.getValue().getKbName());
        assertEquals("u1", chunkCaptor.getValue().getLastuser());

        ArgumentCaptor<KbDocBean> docCaptor = ArgumentCaptor.forClass(KbDocBean.class);
        verify(kbDocBeanMapper).delete(docCaptor.capture());
        assertEquals("研发知识库", docCaptor.getValue().getKbName());
        assertEquals("u1", docCaptor.getValue().getLastuser());

        ArgumentCaptor<KbDocContentBean> contentCaptor =
                ArgumentCaptor.forClass(KbDocContentBean.class);
        verify(kbDocContentBeanMapper).delete(contentCaptor.capture());
        assertEquals("研发知识库", contentCaptor.getValue().getKbName());
        assertEquals("u1", contentCaptor.getValue().getLastuser());

        // 先清分块与原始正文、再清文档、最后删权限记录：中途失败时主记录仍在，可重试
        InOrder order = inOrder(kbChunkBeanMapper, kbDocContentBeanMapper,
                kbDocBeanMapper, knowledgeBaseBeanMapper);
        order.verify(kbChunkBeanMapper).delete(any(KbChunkBean.class));
        order.verify(kbDocContentBeanMapper).delete(any(KbDocContentBean.class));
        order.verify(kbDocBeanMapper).delete(any(KbDocBean.class));
        order.verify(knowledgeBaseBeanMapper).delete(any(KnowledgeBaseBean.class));
    }

    /** 管理员代删他人知识库时，级联条件必须用记录的所有者，而不是管理员的 uid */
    @Test
    void delete_shouldCascadeWithRecordOwnerWhenAdminDeletes() {
        KnowledgeBaseBean target = record("kb-1", "u2", "public", "[]", "[]");
        target.setIid(1L);
        target.setName("客服知识库");
        when(myUserBaseDbHolder.queryUserByUid("admin")).thenReturn(user("admin", 99999L, "dept00"));
        when(knowledgeBaseBeanMapper.select(isNull(), any(KnowledgeBaseBean.class)))
                .thenReturn(Collections.singletonList(target));
        when(kbChunkBeanMapper.delete(any(KbChunkBean.class))).thenReturn(1);
        when(kbDocBeanMapper.delete(any(KbDocBean.class))).thenReturn(1);
        when(knowledgeBaseBeanMapper.delete(any(KnowledgeBaseBean.class))).thenReturn(1);

        KnowledgeBaseBean request = new KnowledgeBaseBean();
        request.setKbId("kb-1");
        holder.delete("admin", request);

        ArgumentCaptor<KbDocBean> docCaptor = ArgumentCaptor.forClass(KbDocBean.class);
        verify(kbDocBeanMapper).delete(docCaptor.capture());
        assertEquals("u2", docCaptor.getValue().getLastuser(),
                "管理员删除时应清理原所有者的本地文档");
    }

    /** 级联清理失败时必须保留主记录：否则用户既看不到文档，也无法重试清理 */
    @Test
    void delete_shouldKeepRecordWhenCascadeFails() {
        KnowledgeBaseBean target = record("kb-1", "u1", "public", "[]", "[]");
        target.setIid(1L);
        target.setName("研发知识库");
        when(myUserBaseDbHolder.queryUserByUid("u1")).thenReturn(user("u1", 5L, "dept01"));
        when(knowledgeBaseBeanMapper.select(isNull(), any(KnowledgeBaseBean.class)))
                .thenReturn(Collections.singletonList(target));
        when(kbChunkBeanMapper.delete(any(KbChunkBean.class)))
                .thenThrow(new RuntimeException("db down"));

        KnowledgeBaseBean request = new KnowledgeBaseBean();
        request.setKbId("kb-1");
        Result<Boolean> result = holder.delete("u1", request);

        assertFalse(Boolean.TRUE.equals(result.getDatas()));
        assertTrue(result.getResp_msg().contains("清理"));
        verify(knowledgeBaseBeanMapper, never()).delete(any(KnowledgeBaseBean.class));
    }

    /** 没有任何定位条件时不能直接透传删除：Mapper 会把空条件当成全表删除 */
    @Test
    void delete_shouldRejectEmptyCondition() {
        Result<Boolean> result = holder.delete("u1", new KnowledgeBaseBean());

        assertFalse(Boolean.TRUE.equals(result.getDatas()));
        verify(knowledgeBaseBeanMapper, never()).delete(any(KnowledgeBaseBean.class));
        verify(knowledgeBaseBeanMapper, never()).select(isNull(), any(KnowledgeBaseBean.class));
    }
}
