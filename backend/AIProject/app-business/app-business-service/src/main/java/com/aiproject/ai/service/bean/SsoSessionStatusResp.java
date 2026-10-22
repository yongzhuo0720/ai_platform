package com.aiproject.ai.service.bean;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * SSO 会话状态查询响应
 *
 * @author
 * @date 2026/08/11
 */
@Data
@ApiModel(value = "SSO 会话状态", description = "SSO 会话状态查询响应")
public class SsoSessionStatusResp {

    @ApiModelProperty(value = "是否有有效的 SSO 会话", example = "true")
    private boolean hasSession;

    @ApiModelProperty(value = "SSO 授权登录地址（hasSession=false 时返回，前端直接跳转）", example = "https://sso.example.com/oauth/login?...")
    private String authUrl;

}
