package com.aiproject.ai.service.controller;

import com.aiproject.ai.permission.holde.MyUserBaseDbHolder;
import com.aiproject.ai.service.bean.AiWeeklyReportBean;
import com.aiproject.ai.service.holde.AiWeeklyReportHolder;
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
 * AI 调用周报：定时生成（周一 02:00），也支持后台手动触发。
 */
@Api(value = "AI 调用周报", tags = "AI 调用周报")
@RestController
@RequestMapping("/api/aiWeeklyReport")
@Slf4j
public class AiWeeklyReportController {

    @Autowired
    AiWeeklyReportHolder holder;

    @Autowired
    MyUserBaseDbHolder userBaseDbHolder;

    @ApiOperation(value = "生成周报（缺省=上一自然周）", httpMethod = "POST")
    @ControllerLog(value = "生成 AI 周报", user = "uid", target = "")
    @PostMapping("/generate")
    public Result<AiWeeklyReportBean> generate(@LoginUserId String uid,
                                               @RequestBody AiWeeklyReportBean data) {
        if (uid == null || uid.trim().isEmpty() || !userBaseDbHolder.isAdmin(uid)) {
            return Result.failed(null, "仅管理员可生成周报");
        }
        return holder.generate(data == null ? null : data.getWeekStart());
    }

    @ApiOperation(value = "周报列表", httpMethod = "POST")
    @PostMapping("/list")
    public Result<List<AiWeeklyReportBean>> list(@LoginUserId String uid) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(null, "请先登录");
        }
        return holder.list();
    }
}
