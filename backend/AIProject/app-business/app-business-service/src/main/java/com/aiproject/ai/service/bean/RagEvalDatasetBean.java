package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * RAG 评估数据集：一组问题与标准答案所在文档。
 *
 * <p>{@code datasetKey} 是服务端生成的稳定业务键。HNSP 的 {@code install}
 * 不回填自增 iid，用业务键关联用例/运行记录，避免「插入后按名称反查最新一行」
 * 这种在并发下会认错行的做法。</p>
 *
 * <p>{@code caseSetKey} 实现用例集的写时复制：编辑用例时先写一套新用例，
 * 全部成功后再切换这个键，最后删除旧用例。没有事务也能保证编辑失败时
 * 旧用例仍然完整可用（项目统一不使用 @Transactional）。</p>
 */
@Data
@SqlBean(keyId = "iid", ignores = {"key1", "key2", "key3"})
@ApiModel(value = "RAG评估数据集", description = "RAG检索评估的数据集")
public class RagEvalDatasetBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty("数据集业务键")
    String datasetKey;

    @SqlColumn()
    @ApiModelProperty("数据集名称")
    String name;

    @SqlColumn()
    @ApiModelProperty("评估所用知识库名称")
    String kbName;

    @SqlColumn(method = "text")
    @ApiModelProperty("数据集说明")
    String description;

    @SqlColumn()
    @ApiModelProperty("当前用例数量")
    Integer caseCount;

    @SqlColumn()
    @ApiModelProperty("当前生效的用例集版本键")
    String caseSetKey;
}
