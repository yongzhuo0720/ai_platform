package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.bean.ChatConversationBean;
import com.aiproject.ai.service.holde.ChatConversationHolder;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.inter.ControllerLog;
import com.heneng.hnsp.common.inter.LoginUserId;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 云端会话同步：直连模型会话的多端持久化。
 *
 * <p>前端策略：本地 localStorage 仍为第一写入位（离线可用），
 * 登录后拉取云端合并；会话增删改时异步 upsert 到这里。</p>
 */
@Api(value = "云端会话", tags = "云端会话")
@RestController
@RequestMapping("/api/chatConversation")
@Slf4j
public class ChatConversationController {

    @Autowired
    ChatConversationHolder holder;

    @ApiOperation(value = "列出我的云端会话", httpMethod = "POST")
    @ControllerLog(value = "列出云端会话", user = "uid", target = "")
    @PostMapping("/list")
    public Result<List<ChatConversationBean>> list(@LoginUserId String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(null, "请先登录");
        }
        return holder.list(uid);
    }

    @ApiOperation(value = "同步（upsert）单个会话", httpMethod = "POST")
    @ControllerLog(value = "同步云端会话", user = "uid", target = "")
    @PostMapping("/save")
    public Result<Boolean> save(@LoginUserId String uid,
                                @RequestBody ChatConversationBean data) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(false, "请先登录");
        }
        // 归属强制为当前用户，防止越权写别人的会话
        data.setUserId(uid);
        return holder.save(uid, data);
    }

    @ApiOperation(value = "删除云端会话", httpMethod = "POST")
    @ControllerLog(value = "删除云端会话", user = "uid", target = "")
    @PostMapping("/delete")
    public Result<Boolean> delete(@LoginUserId String uid,
                                  @RequestBody ChatConversationBean data) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(false, "请先登录");
        }
        return holder.delete(uid, data.getConvId());
    }
}
