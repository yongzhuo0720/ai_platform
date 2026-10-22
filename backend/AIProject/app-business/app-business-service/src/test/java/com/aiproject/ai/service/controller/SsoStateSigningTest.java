package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.config.SsoProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * SSO state 签名 / 验签的单元测试。
 *
 * 背景（对应 docs/code-review/2026-10-17/04-backend.md 高危 #5）：
 * 修复前 state 就是裸 uid，回调时原样当 uid 用。攻击者拿自己的授权 code 配上
 * 受害者的 uid 请求一次回调，就能把令牌写到受害者账号上（Token 错绑 / 会话固定）。
 *
 * 现在 state = {uid}.{HmacSHA256(uid, clientSecret)}，回调时验签。
 * 这几个用例就是模拟攻击者的几种打法，验证都拦得住。
 */
@ExtendWith(MockitoExtension.class)
class SsoStateSigningTest {

    @Mock
    SsoProperties ssoProperties;

    @InjectMocks
    SsoTokenBeanController controller;

    /** 统一注入签名密钥，模拟真实部署里的 clientSecret */
    private void givenSecret() {
        when(ssoProperties.getClientSecret()).thenReturn("test-client-secret");
    }

    /** 正常流程：签发的 state 能解析回原来的 uid */
    @Test
    void signedState_shouldRoundTrip() {
        givenSecret();
        String state = controller.buildSignedState("u1001");
        assertEquals("u1001", controller.parseUidFromState(state));
    }

    /**
     * 攻击者知道目标 uid，但没有 clientSecret，签不出正确签名。
     * 这是最主要的一条攻击路径。
     */
    @Test
    void shouldReject_forgedSignature() {
        givenSecret();
        assertNull(controller.parseUidFromState("u1001.abc123"));
        assertNull(controller.parseUidFromState("u1001."));
        assertNull(controller.parseUidFromState("u1001"));
    }

    /**
     * 攻击者截获了一个合法 state，把 uid 换成受害者、签名留着原来的。
     * uid 一变签名就对不上，必须拒绝。
     */
    @Test
    void shouldReject_tamperedUid() {
        givenSecret();
        String state = controller.buildSignedState("attacker");
        String sig = state.substring(state.indexOf('.') + 1);
        assertNull(controller.parseUidFromState("victim." + sig));
    }

    /** 签名密钥不同（比如换了一份 clientSecret），旧 state 不应通过 */
    @Test
    void shouldReject_stateSignedWithDifferentSecret() {
        givenSecret();
        String state = controller.buildSignedState("u1001");

        when(ssoProperties.getClientSecret()).thenReturn("another-secret");
        assertNull(controller.parseUidFromState(state));
    }

    /**
     * 畸形输入一律按无效处理，不能抛异常把回调打成 500。
     * <p>
     * 注意这里不注入 clientSecret：这些输入在解析阶段就提前返回了，
     * 根本走不到签名那一步（注入了反而会触发 Mockito 的 UnnecessaryStubbing）。
     */
    @Test
    void shouldReject_malformedState() {
        assertNull(controller.parseUidFromState(null));
        assertNull(controller.parseUidFromState(""));
        assertNull(controller.parseUidFromState("no-separator"));
        assertNull(controller.parseUidFromState(".onlysignature"));
    }

    /** 签名本身要稳定：同一个 uid 多次签发得到相同结果（否则回调会不稳定） */
    @Test
    void signature_shouldBeStable() {
        givenSecret();
        assertEquals(controller.signState("u1001"), controller.signState("u1001"));
    }
}
