package com.aiproject.ai.service.controller;

import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.inter.LoginUserId;
import com.heneng.hnsp.common.model.CodeEnum;
import io.swagger.annotations.ApiParam;
import org.springframework.web.bind.annotation.RequestBody;
import lombok.extern.slf4j.Slf4j;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import com.heneng.hnsp.common.inter.ControllerLog;
import blxt.qjava.utils.check.CheckUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.beans.factory.annotation.Autowired;
import io.swagger.annotations.Api;
import com.aiproject.ai.service.holde.KnowledgeBaseMemberHolder;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@Slf4j
@RequestMapping("/api/knowledgeBaseMember")
@Api(value = "知识库成员", tags = "知识库成员")
public class KnowledgeBaseMemberController {

    @Autowired
    KnowledgeBaseMemberHolder knowledgeBaseMemberHolder = null;

    @ApiOperation(value = "加入知识库", httpMethod = "POST", notes = "加入知识库")
    @PostMapping("/join")
    @ControllerLog(value = "加入知识库", user = "uid", target = "")
    public Result<Boolean> join(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody JoinRequest data) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        CheckUtils.objectCheckNull(data.getKbId(), "kbId不能空", CodeEnum.NULL.getCode(), null);
        return knowledgeBaseMemberHolder.join(data.getKbId(), uid);
    }

    @ApiOperation(value = "退出知识库", httpMethod = "POST", notes = "退出知识库")
    @PostMapping("/leave")
    @ControllerLog(value = "退出知识库", user = "uid", target = "")
    public Result<Boolean> leave(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody JoinRequest data) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        CheckUtils.objectCheckNull(data.getKbId(), "kbId不能空", CodeEnum.NULL.getCode(), null);
        return knowledgeBaseMemberHolder.leave(data.getKbId(), uid);
    }

    @ApiOperation(value = "移除成员（仅所有者）", httpMethod = "POST", notes = "移除成员")
    @PostMapping("/remove")
    @ControllerLog(value = "移除知识库成员", user = "uid", target = "")
    public Result<Boolean> remove(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody RemoveRequest data) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        CheckUtils.objectCheckNull(data.getKbId(), "kbId不能空", CodeEnum.NULL.getCode(), null);
        CheckUtils.objectCheckNull(data.getUserId(), "userId不能空", CodeEnum.NULL.getCode(), null);
        return knowledgeBaseMemberHolder.removeMember(data.getKbId(), data.getUserId(), uid);
    }

    @ApiOperation(value = "获取成员列表", httpMethod = "POST", notes = "获取成员列表")
    @PostMapping("/members")
    @ControllerLog(value = "查询知识库成员", user = "uid", target = "")
    public Result<List<Map<String, String>>> members(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody JoinRequest data) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        CheckUtils.objectCheckNull(data, "数据不能空", CodeEnum.NULL.getCode(), null);
        CheckUtils.objectCheckNull(data.getKbId(), "kbId不能空", CodeEnum.NULL.getCode(), null);
        return knowledgeBaseMemberHolder.getMembers(data.getKbId(), uid);
    }

    @ApiOperation(value = "获取已加入的知识库ID", httpMethod = "POST", notes = "获取已加入的知识库ID")
    @PostMapping("/joinedIds")
    @ControllerLog(value = "查询已加入知识库ID", user = "uid", target = "")
    public Result<List<String>> joinedIds(@LoginUserId String uid, @ApiParam(required = true, value = "数据") @RequestBody JoinedIdsRequest data) {
        CheckUtils.objectCheckNull(uid, "请先登录", CodeEnum.FAILED_USER_LOGIN.getCode(), "");
        Set<String> joinedIds = knowledgeBaseMemberHolder.getJoinedKbIds(uid);
        if (data != null && data.getKbIds() != null && !data.getKbIds().isEmpty()) {
            joinedIds.retainAll(data.getKbIds());
        }
        return Result.succeed(new java.util.ArrayList<>(joinedIds), "查询成功");
    }

    // 请求DTO内嵌定义
    @lombok.Data
    public static class JoinRequest {
        String kbId;
    }

    @lombok.Data
    public static class RemoveRequest {
        String kbId;
        String userId;
    }

    @lombok.Data
    public static class JoinedIdsRequest {
        List<String> kbIds;
    }
}
