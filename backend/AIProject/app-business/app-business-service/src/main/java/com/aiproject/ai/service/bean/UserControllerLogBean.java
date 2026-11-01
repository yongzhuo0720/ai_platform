package com.aiproject.ai.service.bean;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.model.DbEntityBase;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 操作日志（框架写入的 usercontrollerlog 表）。
 *
 * <p><b>这是一张只读表。</b>写入方是 HNSP 框架的接口日志切面——它按
 * {@code @ApiOperation(value)} 生成 action、按方法执行耗时生成 time，
 * 我们的代码只负责查。因此这里不注册任何新增/修改/删除入口。</p>
 *
 * <p><b>{@code value} 必须显式写成 {@code usercontrollerlog}。</b>框架的
 * {@code SqlFieldTools.getTableName(Class)} 取的就是 {@code @SqlBean.value}，
 * 空着才回退到类名小写；本项目其他 Bean 的表名都等于类名小写所以从不写，
 * 这张框架建的表名字对不上，不写就会查 {@code usercontrollerlogbean}。
 * {@code @HnMapper(tablename)} 也一并写上同一个值，两处保持一致。</p>
 *
 * <p><b>为什么字段全是小写。</b>{@code usercontrollerlog} 由框架按字段名原样建表，
 * 列名清一色小写（{@code uaccount}、{@code clientip}……），和本项目自己的驼峰表
 * （{@code aicalllogbean.userId}）不同。Java 字段必须与列名逐字相等，
 * 写成 {@code clientIp} 就会去查一个不存在的 {@code "clientIp"} 列。</p>
 *
 * <p><b>故意不映射 {@code params}。</b>该列设计上用于记录请求参数，一旦某天的
 * 登录/改密接口被切面记录，里面就是明文口令。日志查询接口的调用方是「看全站行为」，
 * 并不需要请求体，所以在实体层面直接不声明这一列：
 * 生成的 SQL 根本不会 SELECT 它，后续无论谁扩展这个接口都不可能把它带出来。
 * {@code target}、{@code msg}、{@code appid}、{@code handler} 四列在现存
 * 1.1 万条记录里恒为空，同样不映射，避免页面上出现一列永远空白。</p>
 */
@Data
@SqlBean(value = "usercontrollerlog", keyId = "iid",
        ignores = {"key1", "key2", "key3", "lastuser", "updatetime", "enable"})
@ApiModel(value = "操作日志", description = "后台接口调用审计（框架写入，本系统只读）")
public class UserControllerLogBean extends DbEntityBase {

    @SqlColumn()
    @ApiModelProperty("日志分类，框架固定写 sys")
    String type;

    @SqlColumn()
    @ApiModelProperty("操作者账号")
    String uaccount;

    @SqlColumn()
    @ApiModelProperty("操作名称，取自接口的 @ApiOperation")
    String action;

    @SqlColumn()
    @ApiModelProperty("被操作记录的 id（仅部分写操作有值）")
    Integer actionid;

    @SqlColumn()
    @ApiModelProperty("操作结果描述，如「删除成功」；失败时为空")
    String res;

    @SqlColumn()
    @ApiModelProperty("结果码（仅部分写操作有值）")
    Integer resid;

    @SqlColumn()
    @ApiModelProperty("服务端IP")
    String serverip;

    @SqlColumn()
    @ApiModelProperty("客户端IP")
    String clientip;

    @SqlColumn()
    @ApiModelProperty("被调用的接口地址")
    String url;

    @SqlColumn()
    @ApiModelProperty("接口执行耗时（毫秒）")
    Integer time;

    @SqlColumn()
    @ApiModelProperty("框架记录格式标识")
    String format;
}
