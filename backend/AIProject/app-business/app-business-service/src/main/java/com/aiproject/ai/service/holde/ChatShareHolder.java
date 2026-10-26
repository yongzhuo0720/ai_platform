package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.ChatShareBean;
import com.aiproject.ai.service.mapper.ChatShareBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 对话分享：create 生成 token 快照；getByToken 只读取出。
 *
 * <p>payload 上限 {@link #MAX_PAYLOAD_CHARS}，与云端会话同一量级护栏。</p>
 */
@Slf4j
@Service
public class ChatShareHolder {

    private static final int MAX_PAYLOAD_CHARS = 512 * 1024;

    @Autowired
    ChatShareBeanMapper<ChatShareBean> mapper;

    public Result<String> create(String uid, String authorName, ChatShareBean data) {
        if (data == null || data.getPayload() == null || data.getPayload().trim().isEmpty()) {
            return Result.failed(null, "分享内容不能为空");
        }
        if (data.getPayload().length() > MAX_PAYLOAD_CHARS) {
            return Result.failed(null, "分享内容过大（超过 512KB）");
        }
        ChatShareBean row = new ChatShareBean();
        row.setShareToken(UUID.randomUUID().toString().replace("-", ""));
        row.setTitle(truncate(data.getTitle() == null || data.getTitle().trim().isEmpty()
                ? "未命名对话" : data.getTitle(), 200));
        row.setPayload(data.getPayload());
        row.setUserId(uid);
        row.setAuthorName(truncate(authorName == null ? "" : authorName, 100));
        row.setLastuser(uid);
        mapper.install(row);
        return Result.succeed(row.getShareToken(), "分享已创建");
    }

    /**
     * 按 token 取分享内容。
     *
     * <p>这是全站少数免登录接口之一：分享链接要发给未注册的访客。
     * 只读、只按随机 token 匹配，不暴露 uid 之外的用户信息。</p>
     */
    public Result<ChatShareBean> getByToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return Result.failed(null, "分享不存在或已失效");
        }
        ChatShareBean where = new ChatShareBean();
        where.setShareToken(token.trim());
        List<ChatShareBean> rows = mapper.select(null, where);
        if (rows == null || rows.isEmpty()) {
            return Result.failed(null, "分享不存在或已失效");
        }
        return Result.succeed(rows.get(0));
    }

    public Result<Boolean> delete(String uid, String token) {
        if (token == null || token.trim().isEmpty()) {
            return Result.failed(false, "分享不存在");
        }
        ChatShareBean where = new ChatShareBean();
        where.setShareToken(token.trim());
        where.setUserId(uid);
        int removed = mapper.delete(where);
        return removed > 0 ? Result.succeed(true, "已取消分享") : Result.failed(false, "分享不存在或无权删除");
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
