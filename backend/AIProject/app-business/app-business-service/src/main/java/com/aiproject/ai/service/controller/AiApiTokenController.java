package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.bean.AiApiTokenBean;
import com.aiproject.ai.service.holde.AiApiTokenHolder;
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
 * 平台 API 密钥管理：给「用自己的程序调平台模型」的人自助签发。
 *
 * <p>库里的行只有 SHA-256 哈希，明文只在 {@code /create} 的响应里出现一次。
 * 因此这里<b>刻意不给 create 加 {@code @ControllerLog}</b>：操作日志会把响应体
 * 整段写进 usercontrollerlog 的 res 列，那等于刚生成的密钥立刻变成
 * 「任何能看操作日志的人都拿得到」，哈希存储的意义就没了。
 * 创建动作本身有 lastuser + createtime 可查，不靠操作日志留痕。</p>
 *
 * <p>归属一律以当前登录 uid 为准，请求体里传的 userId 直接覆盖，
 * 避免「替别人建密钥」或「改到别人的密钥」。</p>
 *
 * <p>请求体形状是 {@code {"data": {...}}}：框架按<b>参数名</b>映射请求体，
 * 裸传 {@code {"name": "..."}} 时 {@code data} 解析成 null（本机 curl 实测）。
 * 这与本项目其它 {@code @RequestBody} 接口一致，前端沿用同一写法即可。</p>
 */
@Api(value = "平台API密钥", tags = "平台API密钥")
@RestController
@RequestMapping("/api/aiApiToken")
@Slf4j
public class AiApiTokenController {

    @Autowired
    AiApiTokenHolder holder;

    @ApiOperation(value = "列出我的密钥", httpMethod = "POST", notes = "只返回展示前缀，不含明文与哈希")
    @ControllerLog(value = "列出平台API密钥", user = "uid", target = "")
    @PostMapping("/list")
    public Result<List<AiApiTokenBean>> list(@LoginUserId String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(null, "请先登录");
        }
        return Result.succeed(holder.listOwned(uid));
    }

    @ApiOperation(value = "创建密钥", httpMethod = "POST",
            notes = "响应里的 plaintext 是唯一一次可见，关闭后只能重建")
    @PostMapping("/create")
    public Result<AiApiTokenHolder.Issued> create(@LoginUserId String uid,
                                                  @RequestBody(required = false) AiApiTokenBean data) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(null, "请先登录");
        }
        if (data == null) {
            return Result.failed(null, "请求体格式不正确：需要 { \"data\": { \"name\": \"...\" } }");
        }
        return holder.create(uid, data.getName(), data.getAllowModels(), data.getExpireAt());
    }

    @ApiOperation(value = "修改密钥（名称 / 白名单 / 过期 / 启停）", httpMethod = "POST")
    @ControllerLog(value = "修改平台API密钥", user = "uid", target = "")
    @PostMapping("/update")
    public Result<Boolean> update(@LoginUserId String uid,
                                  @RequestBody AiApiTokenBean data) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(false, "请先登录");
        }
        if (data == null) {
            return Result.failed(false, "请求体格式不正确：需要 { \"data\": { \"iid\": 1, ... } }");
        }
        return holder.updateOwn(uid, data);
    }

    @ApiOperation(value = "删除密钥", httpMethod = "POST", notes = "删除后立即失效，正在进行的流不受影响")
    @ControllerLog(value = "删除平台API密钥", user = "uid", target = "")
    @PostMapping("/delete")
    public Result<Boolean> delete(@LoginUserId String uid,
                                  @RequestBody AiApiTokenBean data) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(false, "请先登录");
        }
        if (data == null) {
            return Result.failed(false, "请求体格式不正确：需要 { \"data\": { \"iid\": 1 } }");
        }
        return holder.delete(uid, data.getIid());
    }
}
