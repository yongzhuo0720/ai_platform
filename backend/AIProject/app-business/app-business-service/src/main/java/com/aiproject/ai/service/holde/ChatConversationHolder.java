package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.ChatConversationBean;
import com.aiproject.ai.service.mapper.ChatConversationBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 云端会话存取：按 (userId, convId) upsert，整会话 JSON 快照。
 *
 * <p>刻意不做消息级的行存储：直连会话的多轮上下文由前端整体持有，
 * 服务端只负责「存与同步」，拆行反而引入前后端双写不一致的问题。
 * 快照上限 {@link #MAX_PAYLOAD_CHARS} 防止异常大对象拖垮表。</p>
 */
@Slf4j
@Service
public class ChatConversationHolder {

    private static final int MAX_PAYLOAD_CHARS = 512 * 1024;
    private static final int MAX_CONVERSATIONS_PER_USER = 500;

    @Autowired
    ChatConversationBeanMapper<ChatConversationBean> mapper;

    /** 列出用户全部云端会话（按客户端更新时间倒序，置顶优先） */
    public Result<List<ChatConversationBean>> list(String uid) {
        ChatConversationBean where = new ChatConversationBean();
        where.setUserId(uid);
        List<ChatConversationBean> rows = mapper.select(null, where);
        if (rows == null) {
            rows = new ArrayList<ChatConversationBean>();
        }
        rows.sort(new Comparator<ChatConversationBean>() {
            @Override
            public int compare(ChatConversationBean a, ChatConversationBean b) {
                boolean pa = Boolean.TRUE.equals(a.getPinned());
                boolean pb = Boolean.TRUE.equals(b.getPinned());
                if (pa != pb) {
                    return pa ? -1 : 1;
                }
                long ta = a.getClientUpdatedAt() == null ? 0 : a.getClientUpdatedAt();
                long tb = b.getClientUpdatedAt() == null ? 0 : b.getClientUpdatedAt();
                return Long.compare(tb, ta);
            }
        });
        return Result.succeed(rows);
    }

    /** upsert 单个会话；超过数量上限时拒绝并提示清理 */
    public Result<Boolean> save(String uid, ChatConversationBean data) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(false, "请先登录");
        }
        if (data == null || data.getConvId() == null || data.getConvId().trim().isEmpty()) {
            return Result.failed(false, "会话 ID 不能为空");
        }
        if (data.getClientUpdatedAt() == null || data.getClientUpdatedAt() <= 0) {
            return Result.failed(false, "缺少有效的会话更新时间");
        }
        if (data.getPayload() != null && data.getPayload().length() > MAX_PAYLOAD_CHARS) {
            return Result.failed(false, "会话内容过大（超过 512KB），请精简后重试");
        }
        ChatConversationBean where = new ChatConversationBean();
        where.setUserId(uid);
        where.setConvId(data.getConvId().trim());
        List<ChatConversationBean> existing = mapper.select(null, where);
        if (existing != null && !existing.isEmpty()) {
            return updateExisting(uid, existing.get(0), data);
        }
        ChatConversationBean owned = new ChatConversationBean();
        owned.setConvId(data.getConvId().trim());
        owned.setUserId(uid);
        owned.setTitle(truncate(data.getTitle(), 200));
        owned.setModelName(truncate(data.getModelName(), 120));
        owned.setPinned(Boolean.TRUE.equals(data.getPinned()));
        owned.setPayload(data.getPayload());
        owned.setClientUpdatedAt(data.getClientUpdatedAt());
        owned.setLastuser(uid);
        // 数量护栏：同一用户最多 500 条云端会话
        List<ChatConversationBean> all = countOf(uid);
        if (all != null && all.size() >= MAX_CONVERSATIONS_PER_USER) {
            return Result.failed(false, "云端会话数量已达上限（" + MAX_CONVERSATIONS_PER_USER + "），请删除部分会话后重试");
        }
        try {
            if (mapper.install(owned) > 0) {
                return Result.succeed(true, "已同步");
            }
        } catch (Exception e) {
            log.warn("[ChatSync] 插入冲突，转为条件更新: uid={}, convId={}, {}",
                    uid, owned.getConvId(), e.getMessage());
        }
        // 并发插入时唯一索引 (userId, convId) 会拦下后到的那条。
        // 重新读取赢家行后按它的版本号做条件更新：比它旧就拒绝，
        // 比它新则覆盖，避免两端同时报错或留下重复会话。
        return updateAfterInsertConflict(uid, data);
    }

    /** 并发插入失败后的补偿：重新读取当前行并按版本条件更新 */
    private Result<Boolean> updateAfterInsertConflict(String uid, ChatConversationBean data) {
        ChatConversationBean where = new ChatConversationBean();
        where.setUserId(uid);
        where.setConvId(data.getConvId().trim());
        List<ChatConversationBean> rows = mapper.select(null, where);
        if (rows == null || rows.isEmpty()) {
            return Result.failed(false, "保存会话失败，请稍后重试");
        }
        return updateExisting(uid, rows.get(0), data);
    }

    /** 更新已存在的会话：版本号作为乐观锁条件，旧快照不能覆盖新内容 */
    private Result<Boolean> updateExisting(String uid, ChatConversationBean row, ChatConversationBean data) {
        if (row.getClientUpdatedAt() != null && row.getClientUpdatedAt() > data.getClientUpdatedAt()) {
            return Result.failed(false, "云端已有更新的会话，请重新拉取后合并");
        }
        ChatConversationBean where = new ChatConversationBean();
        where.setUserId(uid);
        where.setConvId(row.getConvId());
        where.setIid(row.getIid());
        where.setClientUpdatedAt(row.getClientUpdatedAt());
        row.setTitle(truncate(data.getTitle(), 200));
        row.setModelName(truncate(data.getModelName(), 120));
        row.setPinned(Boolean.TRUE.equals(data.getPinned()));
        row.setPayload(data.getPayload());
        row.setClientUpdatedAt(data.getClientUpdatedAt());
        row.setLastuser(uid);
        if (mapper.update(row, where) <= 0) {
            return Result.failed(false, "会话已变更或同步失败，请重新拉取后重试");
        }
        return Result.succeed(true, "已同步");
    }

    /** 删除指定会话（只能删自己的） */
    public Result<Boolean> delete(String uid, String convId) {
        if (convId == null || convId.trim().isEmpty()) {
            return Result.failed(false, "会话 ID 不能为空");
        }
        ChatConversationBean where = new ChatConversationBean();
        where.setUserId(uid);
        where.setConvId(convId.trim());
        int removed = mapper.delete(where);
        return removed > 0 ? Result.succeed(true, "已删除") : Result.failed(false, "会话不存在");
    }

    /** 当前用户的全部会话行（数量统计用） */
    private List<ChatConversationBean> countOf(String uid) {
        ChatConversationBean where = new ChatConversationBean();
        where.setUserId(uid);
        return mapper.select(null, where);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) : value;
    }
}
