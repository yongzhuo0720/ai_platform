package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.gateway.AiGatewayAsyncDispatcher;
import com.aiproject.ai.service.gateway.AiGatewayService;
import com.heneng.hnsp.common.inter.LoginUserId;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncTask;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 浏览器访问 Dify 的统一入口。
 * <p>
 * 路径规则：/api/aiGateway/{module}/**，module 取值 chat / knowledgeChat / file，
 * 剩余路径原样转发给 Dify（例如 /api/aiGateway/chat/chat-messages -> {apiUrl}/chat-messages）。
 * <p>
 * 这里不写 @ControllerLog：流式响应可能持续几分钟，操作日志没有意义，
 * 调用审计由 AiGatewayService 写入 aicalllogbean。
 */
@RestController
@Slf4j
@RequestMapping("/api/aiGateway")
@Api(value = "AI调用网关", tags = "AI调用网关")
public class AiGatewayController {

    @Autowired
    AiGatewayService aiGatewayService;

    @Autowired
    AiGatewayAsyncDispatcher asyncDispatcher;

    @ApiOperation(value = "Dify 调用透传", httpMethod = "POST", notes = "支持 SSE 流式与普通 JSON")
    @RequestMapping("/{module}/**")
    public WebAsyncTask<Void> proxy(@PathVariable("module") String module,
                                    @LoginUserId String uid,
                                    HttpServletRequest request,
                                    HttpServletResponse response) {
        return asyncDispatcher.dispatch(module, request,
                gate -> aiGatewayService.proxy(module, uid, request, response, gate));
    }
}
