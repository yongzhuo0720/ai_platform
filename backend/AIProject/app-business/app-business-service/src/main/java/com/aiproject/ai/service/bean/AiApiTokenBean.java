package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 平台 API 令牌：让外部程序用 OpenAI 协议直接调用本网关。
 *
 * <p>只存 {@code tokenHash}（SHA-256 十六进制），不存明文——数据库被读走
 * 也拿不到可用的 Key。明文只在创建时返回一次，丢了只能重建。</p>
 *
 * <p>令牌本身就是身份：命中后以 {@code userId} 的身份走限流、配额与审计，
 * 所以外部程序消耗的额度与浏览器登录用户完全同一套账。</p>
 */
@Data
// 必须显式声明 keyId 与 ignores：DbEntityBase 自带 key1~key3 且默认参与 SQL，
// 不忽略会让查询引用表里不存在的列；不声明 keyId 则框架建表时主键名解析为 null。
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
/** 审计字段（iid/lastuser/createtime/updatetime/enable/app）继承自 DbEntityBase，由 @HnMapper 映射同名列 */
@ApiModel(value = "AI平台API令牌", description = "OpenAI 兼容端点的访问凭证（仅存哈希）")
public class AiApiTokenBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty("令牌名称，便于区分用途")
    String name;

    @SqlColumn()
    @ApiModelProperty("令牌明文的 SHA-256 十六进制，唯一")
    String tokenHash;

    @SqlColumn()
    @ApiModelProperty("展示用前缀，如 sk-1a2b3c4d")
    String tokenPrefix;

    @SqlColumn()
    @ApiModelProperty("归属用户 uid，调用时以此身份计费与限流")
    String userId;

    @SqlColumn(method = "text")
    @ApiModelProperty("允许调用的模型（逗号分隔的平台模型名或 iid），空表示全部")
    String allowModels;

    @SqlColumn()
    @ApiModelProperty("过期时刻（epoch 毫秒），空表示不过期")
    Long expireAt;

    @SqlColumn()
    @ApiModelProperty("最后一次成功鉴权的时刻（epoch 毫秒）")
    Long lastUsedAt;
}
