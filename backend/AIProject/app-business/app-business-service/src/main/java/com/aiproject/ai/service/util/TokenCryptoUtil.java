package com.aiproject.ai.service.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * SSO Token 字段级加密工具（可插拔开关）。
 *
 * <p>设计要点（对应 docs/第二轮深度升级-审查报告 §7 与通俗版第九节第 7 条）：</p>
 * <ul>
 *   <li>密钥来自配置 {@code sso.token-encrypt-key}；为空时禁用加密，token 保持明文入库，
 *       完全兼容存量部署——不配置就等价于升级前行为，绝不破坏现有登录。</li>
 *   <li>启用后用 AES/CBC/PKCS5Padding + 每条记录随机 IV（CBC 模式不能用固定 IV，
 *       否则相同明文产出相同密文，会泄露 token 是否变化）。</li>
 *   <li>密文格式 {@code ENC:base64(iv):base64(cipher)}。{@link #decrypt(String)} 见到非 ENC
 *       前缀就原样返回，因此存量明文可读；配合 Holder 的「写时加密」，实现读时迁移、
 *       无需停机刷库。</li>
 * </ul>
 *
 * <p>说明：当前 Hutool 5.8.4 的 Mode 枚举不含 GCM，故用 CBC+随机 IV。
 * 若后续引入 BouncyCastle，可在不改动调用方的前提下把算法升级为 AES-GCM。</p>
 */
@Slf4j
@Component
public class TokenCryptoUtil {

    private static final String PREFIX = "ENC:";
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/CBC/PKCS5Padding";
    private static final int IV_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 为空表示禁用加密（明文模式）。密钥应为 16/24/32 字节 UTF-8 字符串。 */
    private final SecretKeySpec key;

    public TokenCryptoUtil(@Value("${sso.token-encrypt-key:}") String encryptKey) {
        if (encryptKey == null || encryptKey.trim().isEmpty()) {
            this.key = null;
            log.warn("SSO Token 字段级加密未启用（sso.token-encrypt-key 未配置），token 仍以明文入库；生产环境务必配置 16/24/32 字节密钥");
        } else {
            byte[] keyBytes = encryptKey.getBytes(StandardCharsets.UTF_8);
            if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
                throw new IllegalStateException("sso.token-encrypt-key 必须是 16/24/32 字节的 UTF-8 字符串（AES 密钥长度），当前为 " + keyBytes.length + " 字节");
            }
            this.key = new SecretKeySpec(keyBytes, ALGORITHM);
            log.info("SSO Token 字段级加密已启用（AES/CBC/PKCS5Padding + 随机 IV）");
        }
    }

    public boolean isEnabled() {
        return key != null;
    }

    /** 加密；未启用或入参为空时原样返回。 */
    public String encrypt(String plain) {
        // 空串必须原样返回：refresh_token 可能为空，若把空串加密成非空密文，
        // hasValidSession 里 !isEmpty() 会把「本来没有 refresh_token」误判为「有」，
        // 进而拿空密文去续期，造成无意义的网络请求与日志噪音。
        if (plain == null || plain.isEmpty() || key == null || plain.startsWith(PREFIX)) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
            byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX
                    + Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("SSO Token 加密失败", e);
        }
    }

    /**
     * 解密；非 ENC 前缀（存量明文）原样返回；未启用加密时也原样返回。
     * 密文损坏时抛异常，避免把错误 token 静默当作有效凭据。
     */
    public String decrypt(String stored) {
        if (stored == null || key == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            String payload = stored.substring(PREFIX.length());
            int sep = payload.indexOf(':');
            if (sep < 0) {
                throw new IllegalArgumentException("密文缺少 IV 分隔符");
            }
            byte[] iv = Base64.getDecoder().decode(payload.substring(0, sep));
            byte[] ciphertext = Base64.getDecoder().decode(payload.substring(sep + 1));
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("SSO Token 解密失败（密钥变更或数据损坏）", e);
        }
    }
}
