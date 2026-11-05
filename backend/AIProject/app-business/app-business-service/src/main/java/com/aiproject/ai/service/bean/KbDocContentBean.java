package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 文档原始正文：异步流水线失败重试时不必让用户重新上传。
 *
 * <p>单独建表而不是塞进 kbdocbean：文档列表、状态轮询只关心元信息，
 * 原始正文单篇最多 20 万字符；和元信息同表会让每次列表查询都把整库正文读进内存。</p>
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "知识库文档正文", description = "本地RAG文档原始文本")
public class KbDocContentBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty("所属文档 iid")
    Long docIid;

    @SqlColumn()
    @ApiModelProperty("所属知识库名称")
    String kbName;

    @SqlColumn(method = "text")
    @ApiModelProperty("解析后的规范化正文")
    String content;

    @SqlColumn()
    @ApiModelProperty("正文 SHA-256（与 kbdocbean.contentHash 一致）")
    String contentHash;
}
