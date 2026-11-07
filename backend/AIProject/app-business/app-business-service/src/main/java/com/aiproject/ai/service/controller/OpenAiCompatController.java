package com.aiproject.ai.service.controller;

import com.aiproject.ai.service.gateway.AiGatewayAsyncDispatcher;
import com.aiproject.ai.service.gateway.AiGatewayService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.WebAsyncTask;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * OpenAI 协议兼容入口，让外部程序直接用平台配好的模型。
 * <p>
 * 路径规则：{@code /v1/chat/completions}、{@code /v1/models}，与 OpenAI 官方一致，
 * 所以任何按 OpenAI 协议写的客户端（SDK、IDE 插件、脚本）只要把 base_url 指到这里、
 * api_key 换成平台的 {@code sk-…} 密钥就能用，不需要装 Dify、也不需要拿到任何上游密钥。
 * <p>
 * 鉴权由 {@code ApiTokenAuthInterceptor} 完成（Bearer 平台 API 密钥），
 * 这里不写 {@code @ControllerLog}：一是流式响应可能持续几分钟，操作日志没有意义；
 * 二是调用方没有浏览器登录态，日志里的 uid 字段本来也是空的。
 * 调用审计统一由 {@code AiGatewayService} 写入 aicalllogbean。
 */
@RestController
@Slf4j
@RequestMapping("/v1")
@Api(value = "OpenAI 兼容端点", tags = "AI 开放接口")
public class OpenAiCompatController {

    private static final String OPENAI_CHAT_LABEL = "v1/chat/completions";

    @Autowired
    AiGatewayService aiGatewayService;

    @Autowired
    AiGatewayAsyncDispatcher asyncDispatcher;

    @ApiOperation(value = "对话补全（OpenAI 协议）", httpMethod = "POST",
            notes = "支持 stream=true 的 SSE 与非流式 JSON")
    @PostMapping("/chat/completions")
    public WebAsyncTask<Void> chatCompletions(HttpServletRequest request,
                                              HttpServletResponse response) {
        return asyncDispatcher.dispatch(OPENAI_CHAT_LABEL, request,
                gate -> aiGatewayService.proxyOpenAiChat(request, response, gate));
    }

    @ApiOperation(value = "可用模型列表（OpenAI 协议）", httpMethod = "GET",
            notes = "只列该密钥允许调用的直连模型")
    @GetMapping("/models")
    public void models(HttpServletRequest request, HttpServletResponse response) {
        aiGatewayService.writeOpenAiModels(request, response);
    }
}
