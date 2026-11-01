package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.AiApiTokenBean;
import com.aiproject.ai.service.mapper.AiApiTokenBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 平台 API 令牌（OpenAI 兼容端点的凭证）。
 *
 * <p><b>为什么只存哈希。</b>这张表在库里，而库会被读——备份、调试、越权查询都算。
 * 存明文等于「拿到数据库就拿到一批能直接调模型、还能花别人额度」的钥匙。
 * SHA-256 单向，验证明文时把输入哈希后按唯一索引查，不需要可逆变换。
 * 代价是明文只在创建时出现一次，丢了只能重建。</p>
 *
 * <p><b>令牌即身份。</b>鉴权成功后以 {@code userId} 作为网关的计费/限流主体，
 * 与浏览器登录调用走同一套账，外部程序不会绕过治理。</p>
 */
@Slf4j
@Service
public class AiApiTokenHolder {

    /** 鉴权拦截器成功后写入请求的属性：令牌记录本身 */
    public static final String ATTR_TOKEN = "aiApiToken";

    /** 鉴权拦截器成功后写入请求的属性：令牌归属的 uid，网关按它计费 */
    public static final String ATTR_UID = "aiApiTokenUid";

    public static final String SECRET_PREFIX = "sk-";

    /** 20 字节随机数 = 40 个十六进制字符 */
    private static final int SECRET_BYTES = 20;

    /** lastUsedAt 的回写节流：一分钟内多次调用只写一次，避免鉴权路径上每条请求都 update */
    private static final long LAST_USED_WRITE_INTERVAL_MS = 60 * 1000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    @Autowired
    AiApiTokenBeanMapper<AiApiTokenBean> tokenMapper;

    /**
     * 创建令牌。
     *
     * @param allowModels 逗号分隔的模型名或 iid，空表示允许该用户可用的全部模型
     * @return 创建结果，其中 {@code plaintext} 是唯一的明文出处
     */
    public Result<Issued> create(String operatorUid, String name, String allowModels, Long expireAt) {
        if (operatorUid == null || operatorUid.trim().isEmpty()) {
            return Result.failed("请先登录");
        }
        String trimmedName = trimToNull(name);
        if (trimmedName == null) {
            return Result.failed("令牌名称不能为空");
        }
        if (expireAt != null && expireAt <= System.currentTimeMillis()) {
            return Result.failed("过期时间必须晚于当前时间");
        }

        String plaintext = SECRET_PREFIX + randomHex();
        AiApiTokenBean bean = new AiApiTokenBean();
        bean.setName(trimmedName);
        bean.setTokenHash(hash(plaintext));
        bean.setTokenPrefix(plaintext.substring(0, Math.min(plaintext.length(), SECRET_PREFIX.length() + 8)));
        bean.setUserId(operatorUid);
        bean.setAllowModels(normalizeAllowModels(allowModels));
        bean.setExpireAt(expireAt);
        bean.setEnable(Boolean.TRUE);
        bean.setLastuser(operatorUid);
        if (tokenMapper.install(bean) <= 0) {
            return Result.failed("创建失败");
        }
        // 响应要说清「这枚令牌长什么样」，不是「服务端怎么校验它」。
        // 哈希跟着响应出去没有任何用处，却多一条泄露路径，口径与 listOwned 一致。
        bean.setTokenHash(null);
        return Result.succeed(new Issued(bean, plaintext), "创建成功，明文只显示这一次");
    }

    /**
     * 校验明文令牌。任何不通过的情况都返回 null，由调用方给出统一的 401：
     * 区分「不存在 / 已停用 / 已过期」会把这张表的存在性泄露给猜测者，
     * 具体原因只写进日志供管理员排查。
     */
    public AiApiTokenBean verify(String plaintext) {
        String token = trimToNull(plaintext);
        if (token == null) {
            return null;
        }
        AiApiTokenBean where = new AiApiTokenBean();
        where.setTokenHash(hash(token));
        List<AiApiTokenBean> rows;
        try {
            rows = tokenMapper.select(null, where);
        } catch (Exception e) {
            // 读不到就拒绝：鉴权 fail-open 等于把网关大门敞开
            log.error("[AiApiToken] 查询令牌失败: {}", e.toString());
            return null;
        }
        if (rows == null || rows.isEmpty()) {
            log.warn("[AiApiToken] 未知令牌, prefix={}", prefixForLog(token));
            return null;
        }
        AiApiTokenBean found = rows.get(0);
        if (Boolean.FALSE.equals(found.getEnable())) {
            log.warn("[AiApiToken] 令牌已停用, iid={}", found.getIid());
            return null;
        }
        if (found.getExpireAt() != null && found.getExpireAt() < System.currentTimeMillis()) {
            log.warn("[AiApiToken] 令牌已过期, iid={}, expireAt={}", found.getIid(), found.getExpireAt());
            return null;
        }
        if (found.getUserId() == null || found.getUserId().trim().isEmpty()) {
            log.warn("[AiApiToken] 令牌缺少归属用户, iid={}", found.getIid());
            return null;
        }
        touchLastUsed(found);
        return found;
    }

    /**
     * 令牌的模型白名单是否放行本次调用。
     * <p>
     * 白名单为空表示不限制。候选名任一命中即放行——同一个模型在平台上
     * 可能被写成 iid、平台名或上游模型名（{@code /v1/models} 列的是平台名，
     * 而浏览器直连走的是 iid），白名单只配了其中一种写法时不该出现
     * 「列表里有、调用却 403」。
     *
     * @param candidates 本次调用的各种模型标识写法，null 项自动跳过
     */
    public boolean allowsModel(AiApiTokenBean token, String... candidates) {
        if (token == null) {
            return false;
        }
        String allow = trimToNull(token.getAllowModels());
        if (allow == null) {
            return true;
        }
        List<String> wanted = new ArrayList<String>();
        for (String candidate : candidates) {
            String trimmed = trimToNull(candidate);
            if (trimmed != null) {
                wanted.add(trimmed);
            }
        }
        for (String allowed : allow.split("[,;\\s]+")) {
            if (allowed.isEmpty()) {
                continue;
            }
            for (String candidate : wanted) {
                if (allowed.equalsIgnoreCase(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 列出某用户名下的令牌；返回前抹掉哈希，只保留展示前缀。 */
    public List<AiApiTokenBean> listOwned(String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return Collections.emptyList();
        }
        AiApiTokenBean where = new AiApiTokenBean();
        where.setUserId(uid);
        List<AiApiTokenBean> rows = tokenMapper.select(null, where);
        if (rows == null) {
            return Collections.emptyList();
        }
        List<AiApiTokenBean> safe = new ArrayList<AiApiTokenBean>(rows.size());
        for (AiApiTokenBean row : rows) {
            row.setTokenHash(null);
            safe.add(row);
        }
        return safe;
    }

    /** 删除自己的令牌。删除后立即失效，正在进行中的流式请求不受影响（已鉴权）。 */
    public Result<Boolean> delete(String uid, Long iid) {
        if (uid == null || uid.trim().isEmpty() || iid == null || iid <= 0) {
            return Result.failed(false, "参数不完整");
        }
        // selectById 来自被擦除的原始类型 SqlMapperInterface，只能显式转型
        AiApiTokenBean target = (AiApiTokenBean) tokenMapper.selectById(iid);
        if (target == null) {
            return Result.failed(false, "令牌不存在");
        }
        if (!uid.equals(target.getUserId())) {
            return Result.failed(false, "只能删除自己的令牌");
        }
        AiApiTokenBean where = new AiApiTokenBean();
        where.setIid(iid);
        if (tokenMapper.delete(where) <= 0) {
            return Result.failed(false, "删除失败");
        }
        return Result.succeed(true, "删除成功");
    }

    /**
     * 编辑名称 / 白名单 / 过期时间 / 启用位。归属与 Key 本身不可改。
     * <p>
     * 框架的 update 只写非空字段，所以这里的语义是「传 null 表示不改」：
     * {@code allowModels} 想清除白名单，传空串而不是 null；
     * 过期时间一旦设了只能改晚，不能清空（要不过期就重建一个令牌）。
     */
    public Result<Boolean> updateOwn(String uid, AiApiTokenBean data) {
        if (uid == null || uid.trim().isEmpty() || data == null || data.getIid() == null) {
            return Result.failed(false, "参数不完整");
        }
        // selectById 来自被擦除的原始类型 SqlMapperInterface，只能显式转型
        AiApiTokenBean target = (AiApiTokenBean) tokenMapper.selectById(data.getIid());
        if (target == null) {
            return Result.failed(false, "令牌不存在");
        }
        if (!uid.equals(target.getUserId())) {
            return Result.failed(false, "只能修改自己的令牌");
        }
        if (data.getExpireAt() != null && data.getExpireAt() <= 0) {
            return Result.failed(false, "过期时间不合法");
        }
        AiApiTokenBean bean = new AiApiTokenBean();
        bean.setName(trimToNull(data.getName()));
        bean.setAllowModels(data.getAllowModels() == null
                ? null : normalizeAllowModels(data.getAllowModels()));
        bean.setExpireAt(data.getExpireAt());
        bean.setEnable(data.getEnable());
        bean.setLastuser(uid);
        AiApiTokenBean where = new AiApiTokenBean();
        where.setIid(data.getIid());
        if (tokenMapper.update(bean, where) <= 0) {
            return Result.failed(false, "保存失败");
        }
        return Result.succeed(true, "保存成功");
    }

    /** 令牌明文 → SHA-256 十六进制。 */
    public static String hash(String plaintext) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(plaintext.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            return hex.toString();
        } catch (Exception e) {
            // SHA-256 与 UTF-8 都是 JDK 必备，走到这里说明环境坏了，绝不能返回明文糊弄过去
            throw new IllegalStateException("无法计算令牌哈希", e);
        }
    }

    private void touchLastUsed(AiApiTokenBean found) {
        long now = System.currentTimeMillis();
        Long last = found.getLastUsedAt();
        if (last != null && now - last < LAST_USED_WRITE_INTERVAL_MS) {
            return;
        }
        try {
            AiApiTokenBean bean = new AiApiTokenBean();
            bean.setLastUsedAt(now);
            AiApiTokenBean where = new AiApiTokenBean();
            where.setIid(found.getIid());
            tokenMapper.update(bean, where);
            found.setLastUsedAt(now);
        } catch (Exception e) {
            // 只是记一笔「最近用过」，失败不该让一次合法调用被拒
            log.warn("[AiApiToken] 回写 lastUsedAt 失败, iid={}: {}", found.getIid(), e.toString());
        }
    }

    private static String randomHex() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return hex.toString();
    }

    /**
     * 白名单规范化：分隔符统一成逗号，空项剔除。
     * 入参为 null 返回 null（不改这一列），入参为空白串返回 ""（明确表示「不限制」）。
     */
    private static String normalizeAllowModels(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder normalized = new StringBuilder();
        for (String part : raw.split("[,;\\s]+")) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            if (normalized.length() > 0) {
                normalized.append(',');
            }
            normalized.append(item);
        }
        return normalized.toString();
    }

    /** 日志里只出现前 10 个字符，避免把完整 Key 写进日志文件 */
    private static String prefixForLog(String token) {
        return token.length() <= 10 ? token : token.substring(0, 10) + "...";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 创建结果：落库的记录 + 唯一一次可见的明文 */
    @Getter
    public static class Issued {
        private final AiApiTokenBean token;
        private final String plaintext;

        public Issued(AiApiTokenBean token, String plaintext) {
            this.token = token;
            this.plaintext = plaintext;
        }
    }
}
