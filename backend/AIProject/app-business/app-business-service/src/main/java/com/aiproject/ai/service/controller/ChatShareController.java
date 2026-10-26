package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.bean.ChatShareBean;
import com.aiproject.ai.service.holde.ChatShareHolder;
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

/**
 * 对话分享：create/delete 需登录；get 免登录（访客打开分享链接）。
 */
@Api(value = "对话分享", tags = "对话分享")
@RestController
@RequestMapping("/api/chatShare")
@Slf4j
public class ChatShareController {

    @Autowired
    ChatShareHolder holder;

    @ApiOperation(value = "创建分享", httpMethod = "POST")
    @ControllerLog(value = "创建对话分享", user = "uid", target = "")
    @PostMapping("/create")
    public Result<String> create(@LoginUserId String uid,
                                 @RequestBody ChatShareBean data) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(null, "请先登录");
        }
        // authorName 由前端放 body（Cookie 的 uname），不再混用 @RequestHeader——
        // HNSP 的多参数解析器对 Header+Body 混合会丢 Body 绑定
        return holder.create(uid, data == null ? null : data.getAuthorName(), data);
    }

    @ApiOperation(value = "访问分享（免登录）", httpMethod = "POST")
    @PostMapping("/get")
    public Result<ChatShareBean> get(@RequestBody ChatShareBean data) {
        return holder.getByToken(data == null ? null : data.getShareToken());
    }

    @ApiOperation(value = "取消分享", httpMethod = "POST")
    @ControllerLog(value = "取消对话分享", user = "uid", target = "")
    @PostMapping("/delete")
    public Result<Boolean> delete(@LoginUserId String uid,
                                  @RequestBody ChatShareBean data) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(false, "请先登录");
        }
        return holder.delete(uid, data == null ? null : data.getShareToken());
    }
}
