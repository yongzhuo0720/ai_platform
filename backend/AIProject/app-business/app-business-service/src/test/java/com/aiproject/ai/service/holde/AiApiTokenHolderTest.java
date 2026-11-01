package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.AiApiTokenBean;
import com.aiproject.ai.service.mapper.AiApiTokenBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台 API 令牌的存储与校验语义。
 *
 * <p>最要紧的一条：明文只在创建时出现过一次，落库、查询条件、列表返回里
 * 都不该有它或它的可逆形式。这条线一旦破了，「库里只存哈希」就是空话，
 * 备份文件、越权查询、操作日志里的任何一个都等于泄露全部密钥。</p>
 */
class AiApiTokenHolderTest {

    /** 框架的成功码由 CodeEnum.SUCCESS 定义，测试引用它而不是硬编码 0 */
    private static final Integer SUCCESS_CODE = com.heneng.hnsp.common.model.CodeEnum.SUCCESS.getCode();

    private AiApiTokenBeanMapper<AiApiTokenBean> mapper;
    private AiApiTokenHolder holder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(AiApiTokenBeanMapper.class);
        holder = new AiApiTokenHolder();
        holder.tokenMapper = mapper;
    }

    @Test
    void hashIsStableSixtyFourHexAndNotReversibleFromOutput() {
        String first = AiApiTokenHolder.hash("sk-abc");
        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"), first);
        assertEquals(first, AiApiTokenHolder.hash("sk-abc"));
        assertNotContainsPlaintext(first);
        assertFalse(first.equals(AiApiTokenHolder.hash("sk-abd")), "不同输入必须不同哈希");
    }

    @Test
    void createStoresOnlyHashAndShortPrefix() {
        // install 之后 holder 会清掉响应里的哈希，所以必须在写库那一刻留下快照
        final String[] hashAtInstall = new String[1];
        when(mapper.install(any(AiApiTokenBean.class))).thenAnswer(invocation -> {
            hashAtInstall[0] = ((AiApiTokenBean) invocation.getArgument(0)).getTokenHash();
            return 1;
        });

        Result<AiApiTokenHolder.Issued> result = holder.create("u1", "ci", "demo, other", null);

        assertTrue(ok(result), result.getResp_msg());
        String plaintext = result.getDatas().getPlaintext();
        assertTrue(plaintext.startsWith("sk-"), plaintext);
        assertEquals("sk-".length() + 40, plaintext.length(), plaintext);

        ArgumentCaptor<AiApiTokenBean> captor = ArgumentCaptor.forClass(AiApiTokenBean.class);
        verify(mapper).install(captor.capture());
        AiApiTokenBean saved = captor.getValue();
        assertNotNull(hashAtInstall[0], "写库时必须带哈希");
        assertEquals(AiApiTokenHolder.hash(plaintext), hashAtInstall[0]);
        assertNotContainsPlaintext(hashAtInstall[0]);
        // 展示前缀只到 sk- + 8 位，够管理员辨认、不够复刻整把钥匙
        assertTrue(plaintext.startsWith(saved.getTokenPrefix()), saved.getTokenPrefix());
        assertEquals("sk-".length() + 8, saved.getTokenPrefix().length());
        assertEquals("u1", saved.getUserId());
        assertEquals("demo,other", saved.getAllowModels());
        assertEquals(Boolean.TRUE, saved.getEnable());
        // 响应体是给前端的，多带一个哈希就是多一条泄露路径
        assertNull(result.getDatas().getToken().getTokenHash(), "创建响应不能回带哈希");
    }

    @Test
    void createRejectsMissingOwnerNameAndPastExpiry() {
        assertFalse(ok(holder.create(null, "ci", null, null)));
        assertFalse(ok(holder.create("  ", "ci", null, null)));
        assertFalse(ok(holder.create("u1", "   ", null, null)));
        assertFalse(ok(holder.create("u1", "ci", null, System.currentTimeMillis() - 1000)));
        verify(mapper, never()).install(any(AiApiTokenBean.class));
    }

    @Test
    void createFailsWhenInsertDidNotHappen() {
        when(mapper.install(any(AiApiTokenBean.class))).thenReturn(0);

        assertFalse(ok(holder.create("u1", "ci", null, null)));
    }

    /** 查库用的条件就是哈希，明文永远不会成为 SQL 参数 */
    @Test
    void verifyLooksUpByHashOnly() {
        AiApiTokenBean row = row("u1", null, null);
        when(mapper.select(isNull(), any(AiApiTokenBean.class)))
                .thenReturn(Collections.singletonList(row));

        assertSame(row, holder.verify("  sk-secret  "));

        ArgumentCaptor<AiApiTokenBean> captor = ArgumentCaptor.forClass(AiApiTokenBean.class);
        verify(mapper).select(isNull(), captor.capture());
        assertEquals(AiApiTokenHolder.hash("sk-secret"), captor.getValue().getTokenHash());
        assertNotContainsPlaintext(captor.getValue().getTokenHash());
    }

    @Test
    void verifyRejectsUnknownDisabledExpiredAndOrphaned() {
        when(mapper.select(isNull(), any(AiApiTokenBean.class))).thenReturn(Collections.emptyList());
        assertNull(holder.verify("sk-nope"));

        when(mapper.select(isNull(), any(AiApiTokenBean.class)))
                .thenReturn(Collections.singletonList(row("u1", Boolean.FALSE, null)));
        assertNull(holder.verify("sk-secret"), "已停用的令牌必须拒绝");

        when(mapper.select(isNull(), any(AiApiTokenBean.class)))
                .thenReturn(Collections.singletonList(row("u1", Boolean.TRUE, System.currentTimeMillis() - 1)));
        assertNull(holder.verify("sk-secret"), "已过期的令牌必须拒绝");

        when(mapper.select(isNull(), any(AiApiTokenBean.class)))
                .thenReturn(Collections.singletonList(row("  ", null, null)));
        assertNull(holder.verify("sk-secret"), "没有归属用户的令牌不能当身份用");

        assertNull(holder.verify(null));
        assertNull(holder.verify("   "));
    }

    /** 查询抛异常时按「无效令牌」处理：鉴权 fail-open 等于把网关大门敞开 */
    @Test
    void verifyFailsClosedWhenStoreThrows() {
        when(mapper.select(isNull(), any(AiApiTokenBean.class)))
                .thenThrow(new IllegalStateException("db down"));

        assertNull(holder.verify("sk-secret"));
    }

    @Test
    void verifyWritesLastUsedOnlyOncePerMinute() {
        long now = System.currentTimeMillis();
        AiApiTokenBean stale = row("u1", null, null);
        stale.setLastUsedAt(now - 120_000L);
        AiApiTokenBean fresh = row("u1", null, null);
        fresh.setLastUsedAt(now - 1000L);

        when(mapper.select(isNull(), any(AiApiTokenBean.class)))
                .thenReturn(Collections.singletonList(stale));
        assertNotNull(holder.verify("sk-a"));
        when(mapper.select(isNull(), any(AiApiTokenBean.class)))
                .thenReturn(Collections.singletonList(fresh));
        assertNotNull(holder.verify("sk-b"));

        // 一分钟内的第二次调用不该再打一次 update，否则鉴权热路径每条请求都写库
        verify(mapper, times(1)).update(any(AiApiTokenBean.class), any(AiApiTokenBean.class));
    }

    @Test
    void lastUsedWriteFailureDoesNotRejectAValidToken() {
        AiApiTokenBean row = row("u1", null, null);
        when(mapper.select(isNull(), any(AiApiTokenBean.class)))
                .thenReturn(Collections.singletonList(row));
        when(mapper.update(any(AiApiTokenBean.class), any(AiApiTokenBean.class)))
                .thenThrow(new IllegalStateException("write failed"));

        assertSame(row, holder.verify("sk-secret"));
    }

    @Test
    void emptyAllowListMeansNoRestriction() {
        assertTrue(holder.allowsModel(tokenWithAllow(null), "anything", "7"));
        assertTrue(holder.allowsModel(tokenWithAllow("   "), "anything"));
    }

    @Test
    void nullTokenIsAlwaysDenied() {
        assertFalse(holder.allowsModel(null, "demo"));
    }

    /** 同一个模型可能被写成 iid、平台名或上游名，任一命中都要放行 */
    @ParameterizedTest
    @ValueSource(strings = {"7", "demo", "up-model", "DEMO", "other, demo"})
    void allowListMatchesAnySpellingOfTheSameModel(String allowModels) {
        assertTrue(holder.allowsModel(tokenWithAllow(allowModels), "7", "demo", "up-model"),
                "应放行: " + allowModels);
    }

    @ParameterizedTest
    @ValueSource(strings = {"other", "8", "up-other"})
    void allowListRejectsOtherModels(String allowModels) {
        assertFalse(holder.allowsModel(tokenWithAllow(allowModels), "7", "demo", "up-model"),
                "应拒绝: " + allowModels);
    }

    @Test
    void listOwnedDropsHashAndIsScopedToCaller() {
        AiApiTokenBean one = row("u1", null, null);
        AiApiTokenBean two = row("u1", null, null);
        two.setTokenHash("deadbeef");
        when(mapper.select(isNull(), any(AiApiTokenBean.class))).thenReturn(Arrays.asList(one, two));

        List<AiApiTokenBean> rows = holder.listOwned("u1");

        assertEquals(2, rows.size());
        for (AiApiTokenBean row : rows) {
            assertNull(row.getTokenHash());
        }
        ArgumentCaptor<AiApiTokenBean> captor = ArgumentCaptor.forClass(AiApiTokenBean.class);
        verify(mapper).select(isNull(), captor.capture());
        assertEquals("u1", captor.getValue().getUserId());

        assertTrue(holder.listOwned(null).isEmpty());
        assertTrue(holder.listOwned("  ").isEmpty());
        verify(mapper, times(1)).select(isNull(), any(AiApiTokenBean.class));
    }

    @Test
    void selectReturningNullIsTreatedAsEmptyList() {
        when(mapper.select(isNull(), any(AiApiTokenBean.class))).thenReturn(null);
        assertTrue(holder.listOwned("u1").isEmpty());
    }

    @Test
    void deleteOnlyRemovesOwnToken() {
        AiApiTokenBean otherOwner = row("u2", null, null);
        otherOwner.setIid(9L);
        when(mapper.selectById(eq(9L))).thenReturn(otherOwner);

        assertFalse(ok(holder.delete("u1", 9L)));
        verify(mapper, never()).delete(any(AiApiTokenBean.class));

        AiApiTokenBean mine = row("u1", null, null);
        mine.setIid(9L);
        when(mapper.selectById(eq(9L))).thenReturn(mine);
        when(mapper.delete(any(AiApiTokenBean.class))).thenReturn(1);

        assertTrue(ok(holder.delete("u1", 9L)));
    }

    @Test
    void deleteRejectsIncompleteArgumentsAndMissingRow() {
        assertFalse(ok(holder.delete(null, 9L)));
        assertFalse(ok(holder.delete("u1", null)));
        assertFalse(ok(holder.delete("u1", 0L)));
        when(mapper.selectById(eq(9L))).thenReturn(null);
        assertFalse(ok(holder.delete("u1", 9L)));
    }

    @Test
    void updateKeepsOwnerAndKeyUntouched() {
        AiApiTokenBean mine = row("u1", null, null);
        mine.setIid(9L);
        when(mapper.selectById(eq(9L))).thenReturn(mine);
        when(mapper.update(any(AiApiTokenBean.class), any(AiApiTokenBean.class))).thenReturn(1);

        AiApiTokenBean patch = new AiApiTokenBean();
        patch.setIid(9L);
        patch.setName("新名字");
        patch.setAllowModels(" demo , other ");
        patch.setTokenHash("attempt-to-overwrite");
        patch.setUserId("attacker");

        assertTrue(ok(holder.updateOwn("u1", patch)));

        ArgumentCaptor<AiApiTokenBean> captor = ArgumentCaptor.forClass(AiApiTokenBean.class);
        verify(mapper).update(captor.capture(), any(AiApiTokenBean.class));
        AiApiTokenBean written = captor.getValue();
        assertEquals("新名字", written.getName());
        assertEquals("demo,other", written.getAllowModels());
        // 框架的 update 只写非空字段，所以哈希与归属保持 null 就是「不改」
        assertNull(written.getTokenHash(), "不允许通过编辑接口替换密钥哈希");
        assertNull(written.getUserId(), "不允许通过编辑接口转移归属");
    }

    @Test
    void updateRejectsOtherOwnersTokenAndBadExpiry() {
        AiApiTokenBean otherOwner = row("u2", null, null);
        otherOwner.setIid(9L);
        when(mapper.selectById(eq(9L))).thenReturn(otherOwner);
        AiApiTokenBean patch = new AiApiTokenBean();
        patch.setIid(9L);
        patch.setName("x");
        assertFalse(ok(holder.updateOwn("u1", patch)));

        AiApiTokenBean mine = row("u1", null, null);
        mine.setIid(9L);
        when(mapper.selectById(eq(9L))).thenReturn(mine);
        patch.setExpireAt(-1L);
        assertFalse(ok(holder.updateOwn("u1", patch)));
        verify(mapper, never()).update(any(AiApiTokenBean.class), any(AiApiTokenBean.class));
    }

    @Test
    void updateWithEmptyAllowModelsClearsTheRestriction() {
        AiApiTokenBean mine = tokenWithAllow("demo");
        mine.setIid(9L);
        when(mapper.selectById(eq(9L))).thenReturn(mine);
        when(mapper.update(any(AiApiTokenBean.class), any(AiApiTokenBean.class))).thenReturn(1);

        AiApiTokenBean patch = new AiApiTokenBean();
        patch.setIid(9L);
        patch.setAllowModels("");
        assertTrue(ok(holder.updateOwn("u1", patch)));

        ArgumentCaptor<AiApiTokenBean> captor = ArgumentCaptor.forClass(AiApiTokenBean.class);
        verify(mapper).update(captor.capture(), any(AiApiTokenBean.class));
        // 空串是「清除白名单」的显式写法，必须落成 ""（null 的语义是「不改这一列」）
        assertEquals("", captor.getValue().getAllowModels());
    }

    /** 只关心白名单时用这个夹具，避免和 enable / expireAt 的位置参数混在一起 */
    private static AiApiTokenBean tokenWithAllow(String allowModels) {
        AiApiTokenBean bean = row("u1", null, null);
        bean.setAllowModels(allowModels);
        return bean;
    }

    private static AiApiTokenBean row(String userId, Boolean enable, Long expireAt) {        AiApiTokenBean bean = new AiApiTokenBean();
        bean.setIid(1L);
        bean.setName("ci");
        bean.setUserId(userId);
        bean.setEnable(enable);
        bean.setExpireAt(expireAt);
        bean.setTokenHash("hash-in-db");
        bean.setTokenPrefix("sk-12345678");
        return bean;
    }

    private static void assertNotContainsPlaintext(String value) {
        assertFalse(value.contains("sk-abc"), "存了可逆的形式就等于存明文: " + value);
    }

    /**
     * 成功判定读框架的 SUCCESS 码，测试不自己猜数字。
     * 全站约定是「HTTP 200 + resp_code 表示成败」，前端 axios.js 也按这个分支。
     */
    private static boolean ok(Result<?> result) {
        return result != null && SUCCESS_CODE.equals(result.getResp_code());
    }
}
