package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlColumn;
import io.swagger.annotations.ApiModelProperty;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import lombok.Data;

/**
 * AI 调用周报：一周的调用量 / Token / 成本 / 失败率快照。
 *
 * <p>由定时任务每周一凌晨生成（也可在后台手动触发），
 * 管理员无需进看板逐项核对就有留档的周期性结论。</p>
 */
@Data
/** 审计字段（iid/lastuser/createtime/updatetime/enable/app）继承自 DbEntityBase，由 @HnMapper 映射同名列 */
public class AiWeeklyReportBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty("周一 00:00（yyyy-MM-dd）")
    String weekStart;

    @SqlColumn()
    @ApiModelProperty("周日 23:59:59（yyyy-MM-dd）")
    String weekEnd;

    @SqlColumn()
    @ApiModelProperty("总调用次数")
    Long totalCalls;

    @SqlColumn()
    @ApiModelProperty("成功次数")
    Long successCalls;

    @SqlColumn()
    @ApiModelProperty("失败/拦截次数")
    Long failedCalls;

    @SqlColumn()
    @ApiModelProperty("Token 合计")
    Long totalTokens;

    @SqlColumn()
    @ApiModelProperty("成本合计（毫分）")
    Long costCents;

    @SqlColumn()
    @ApiModelProperty("平均耗时（毫秒）")
    Long avgLatencyMs;

    @SqlColumn()
    @ApiModelProperty("平均首字耗时（毫秒，仅流式）")
    Long avgFirstTokenMs;

    @SqlColumn()
    @ApiModelProperty("调用最多的用户")
    String topUser;

    @SqlColumn()
    @ApiModelProperty("调用最多的模型")
    String topModel;

}
