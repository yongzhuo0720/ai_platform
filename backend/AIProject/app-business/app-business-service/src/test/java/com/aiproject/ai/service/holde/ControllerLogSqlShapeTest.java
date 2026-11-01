package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.UserControllerLogBean;
import com.heneng.hnsp.common.db.utils.SqlFieldTools;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 操作日志实体生成出来的 SQL 片段形状。
 *
 * <p>{@code usercontrollerlog} 是框架建的表，本项目不建表也不改表，
 * 所以实体一旦多声明一个表里没有的列，报错会发生在运行时的一次查询上，
 * 而不是编译期。这个用例把「实体 ↔ 表列」的对应关系钉死在这里：</p>
 * <ul>
 *   <li>{@code params} 绝不能出现在查询列里——它是请求参数快照，
 *       一旦哪个写接口的入参被记录，里面可能是明文口令；</li>
 *   <li>{@code lastuser}、{@code updatetime}、{@code enable} 是基类字段，
 *       这张表没有这几列，必须留在 {@code @SqlBean(ignores)} 里；</li>
 *   <li>页面上要用的列必须真的被选中，否则表格会是一片空白。</li>
 * </ul>
 */
class ControllerLogSqlShapeTest {

    @Test
    void selectColumnsCoverTheTableAndExcludeRequestParams() {
        String columns = SqlFieldTools.getSelectColumns("usercontrollerlog", UserControllerLogBean.class, false);
        String lower = columns.toLowerCase();

        for (String required : new String[]{"uaccount", "action", "clientip", "serverip", "url",
                "res", "time", "createtime", "iid"}) {
            assertTrue(lower.contains(required), "缺少列 " + required + " -> " + columns);
        }

        for (String forbidden : new String[]{"params", "lastuser", "updatetime", "enable", "key1", "key2", "key3"}) {
            assertFalse(lower.contains(forbidden), "不该出现在查询列里: " + forbidden + " -> " + columns);
        }
    }

    @Test
    void tableNameAndKeyIdComeFromAnnotations() {
        // 表名不能按类名推导成 usercontrollerlogbean，那是框架表名之外的另一张表
        assertTrue("usercontrollerlog".equalsIgnoreCase(SqlFieldTools.getTableName(UserControllerLogBean.class)),
                SqlFieldTools.getTableName(UserControllerLogBean.class));
        assertTrue("iid".equalsIgnoreCase(SqlFieldTools.getKeyId(UserControllerLogBean.class)),
                SqlFieldTools.getKeyId(UserControllerLogBean.class));
    }
}
