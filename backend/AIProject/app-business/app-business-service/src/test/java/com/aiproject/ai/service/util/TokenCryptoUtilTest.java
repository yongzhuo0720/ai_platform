package com.aiproject.ai.service.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SSO Token 字段级加密工具单测。
 * 不依赖 Spring 容器，直接 new 实例，覆盖开关、往返、随机 IV、存量兼容与异常。
 */
class TokenCryptoUtilTest {

    private static final String KEY_16 = "1234567890abcdef";

    @Test
    void disabled_whenNoKey_keepsPlaintext() {
        TokenCryptoUtil util = new TokenCryptoUtil("");
        assertFalse(util.isEnabled());
        assertEquals("plain-token", util.encrypt("plain-token"));
        assertEquals("plain-token", util.decrypt("plain-token"));
    }

    @Test
    void enabled_whenKeyProvided() {
        assertTrue(new TokenCryptoUtil(KEY_16).isEnabled());
    }

    @Test
    void encryptThenDecrypt_roundTrips() {
        TokenCryptoUtil util = new TokenCryptoUtil(KEY_16);
        String token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.signature";
        String encrypted = util.encrypt(token);
        assertTrue(encrypted.startsWith("ENC:"), "密文应带 ENC 前缀");
        assertNotEquals(token, encrypted, "密文不应等于明文");
        assertEquals(token, util.decrypt(encrypted));
    }

    @Test
    void samePlaintext_producesDifferentCiphertext_dueToRandomIv() {
        TokenCryptoUtil util = new TokenCryptoUtil(KEY_16);
        String a = util.encrypt("same-token");
        String b = util.encrypt("same-token");
        assertNotEquals(a, b, "CBC 必须每记录随机 IV，否则相同明文密文相同");
        assertEquals("same-token", util.decrypt(a));
        assertEquals("same-token", util.decrypt(b));
    }

    @Test
    void decrypt_legacyPlaintext_returnsAsIs() {
        TokenCryptoUtil util = new TokenCryptoUtil(KEY_16);
        assertEquals("legacy-plain-token", util.decrypt("legacy-plain-token"));
    }

    @Test
    void nullAndEmpty_handled() {
        TokenCryptoUtil util = new TokenCryptoUtil(KEY_16);
        assertNull(util.encrypt(null));
        assertNull(util.decrypt(null));
        assertEquals("", util.encrypt(""));
    }

    @Test
    void encrypt_isIdempotentForAlreadyEncrypted() {
        TokenCryptoUtil util = new TokenCryptoUtil(KEY_16);
        String once = util.encrypt("token");
        assertEquals(once, util.encrypt(once), "已是密文不应二次加密");
    }

    @Test
    void decrypt_corruptCiphertext_throws() {
        TokenCryptoUtil util = new TokenCryptoUtil(KEY_16);
        assertThrows(IllegalStateException.class, () -> util.decrypt("ENC:not-base64:broken"));
    }

    @Test
    void constructor_invalidKeyLength_throws() {
        assertThrows(IllegalStateException.class, () -> new TokenCryptoUtil("too-short-key"));
    }

    @Test
    void wrongKey_cannotDecrypt_throws() {
        String encrypted = new TokenCryptoUtil(KEY_16).encrypt("secret");
        TokenCryptoUtil other = new TokenCryptoUtil("abcdef1234567890");
        assertThrows(IllegalStateException.class, () -> other.decrypt(encrypted));
    }
}
