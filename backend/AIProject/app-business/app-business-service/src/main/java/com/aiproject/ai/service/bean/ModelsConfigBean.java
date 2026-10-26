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

    /**
     * 输入 token 单价，单位「分 / 百万 tokens」。
     *
     * <p>用整数分而不是浮点元：单价是人工配置的小数（如 0.002 元/千 token），
     * 浮点累加会有误差，而分是天然整数。为空表示未配置价格，
     * 该模型的调用成本记为 0，治理看板会显示「未配置价格」。</p>
     */
    @SqlColumn()
    @ApiModelProperty("输入token单价（分/百万tokens），空=未配置")
    Long priceInputPerMillion;

    /** 输出 token 单价，单位「分 / 百万 tokens」。通常比输入贵 */
    @SqlColumn()
    @ApiModelProperty("输出token单价（分/百万tokens），空=未配置")
    Long priceOutputPerMillion;

}
