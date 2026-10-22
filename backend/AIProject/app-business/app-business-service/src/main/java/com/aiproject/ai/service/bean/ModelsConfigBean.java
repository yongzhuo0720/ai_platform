package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SelectLike;
import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * @author jiaoyu
 * @ClassName ModelsConfigBean
 * @description: TODO
 * @date 2026年05月13日
 * @version: 1.0
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "模型配置", description = "模型配置")
public class ModelsConfigBean extends DbEntityBase {
    @SelectLike
    @SqlColumn()
    @ApiModelProperty(value = "id")
    String id;

    @SelectLike
    @SqlColumn()
    @ApiModelProperty(value = "模型名称")
    String name;

    @SqlColumn()
    @ApiModelProperty(value = "提供商")
    String provider;

    /** 协议：空 = 仅作为 Dify 应用变量；openai = OpenAI 兼容直连（可对话） */
    @SqlColumn()
    @ApiModelProperty(value = "直连协议（openai）")
    String protocol;

    @SqlColumn()
    @ApiModelProperty(value = "OpenAI 兼容服务地址（如 https://api.deepseek.com）")
    String baseUrl;

    @SqlColumn()
    @ApiModelProperty(value = "服务密钥（不下发浏览器）")
    String apiKey;

    @SqlColumn()
    @ApiModelProperty(value = "上游真实模型名（如 deepseek-chat）")
    String upstreamModel;

    @SqlColumn()
    @ApiModelProperty("向量模型（本地RAG用，空=不可用于向量化）")
    String embedModel;

}
