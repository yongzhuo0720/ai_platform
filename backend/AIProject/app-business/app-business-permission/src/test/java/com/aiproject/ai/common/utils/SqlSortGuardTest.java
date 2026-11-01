package com.aiproject.ai.common.utils;

import com.aiproject.ai.permission.bean.MyUserBaseDb;
import com.heneng.hnsp.common.db.utils.PageRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link SqlSortGuard} 测试（2026-11-01 新增）。
 *
 * <p>背景：框架把 {@code PageRequest.order / group / desc} 原样拼进 SQL 的
 * {@code ORDER BY} / {@code GROUP BY} 尾部，而这三个字段来自请求体。
 * 本机实测过：{@code order=1/(CASE WHEN 1=1 THEN 0 ELSE 1 END)} 会原样落到 SQL 里
 * 并被数据库执行（回报「除以零」）。这组测试锁住两件事：</p>
 * <ol>
 *   <li>任何不是「该 Bean 真实列名」的排序/分组/方向值都会被拒；</li>
 *   <li>合法排序照常通过，不能被白名单误伤成「列表页排不了序」。</li>
 * </ol>
 */
class SqlSortGuardTest {

    private static PageRequest page(String order, String group, String desc) {
        PageRequest pageRequest = new PageRequest();
        pageRequest.setOrder(order);
        pageRequest.setGroup(group);
        pageRequest.setDesc(desc);
        return pageRequest;
    }

    /** 用户表联表查出 rname/gname 两个别名列，测试里按真实入口的放行清单传入 */
    private static void assertRejected(PageRequest pageRequest) {
        assertThrows(IllegalArgumentException.class,
                () -> SqlSortGuard.guard(pageRequest, MyUserBaseDb.class, "rname", "gname"));
    }

    // ---------- 注入载荷必须被拒 ----------

    @Test
    void order_expressionPayload_shouldBeRejected() {
        assertRejected(page("1/(CASE WHEN 1=1 THEN 0 ELSE 1 END)", null, "asc"));
    }

    @Test
    void order_subqueryPayload_shouldBeRejected() {
        assertRejected(page("(SELECT usename FROM pg_user)", null, "asc"));
    }

    @Test
    void order_stackedStatement_shouldBeRejected() {
        assertRejected(page("uid; DROP TABLE userbase", null, "asc"));
    }

    @Test
    void order_quoteAndCommentPayloads_shouldBeRejected() {
        assertRejected(page("uid' --", null, "asc"));
        assertRejected(page("uid/*x*/", null, "asc"));
    }

    @Test
    void group_injectionPayload_shouldBeRejected() {
        assertRejected(page(null, "gid UNION SELECT 1", null));
    }

    @Test
    void desc_frameworkGetterAlreadyCollapsesPayloads() {
        // 实测框架行为：getDesc() 不是普通 getter，而是
        //   desc == null || desc.startsWith("asc") ? "asc" : "desc"
        // 所以 desc 通道今天带不进任何载荷，真正的注入面只有 order / group。
        PageRequest pageRequest = new PageRequest();
        pageRequest.setDesc("asc; DELETE FROM userbase");
        assertEquals("asc", pageRequest.getDesc());
        pageRequest.setDesc("desc, (SELECT usename FROM pg_user)");
        assertEquals("desc", pageRequest.getDesc());
    }

    @Test
    void desc_tripwire_shouldRejectPayloadIfGetterEverBecomesPlainRead() {
        // 给框架升级上保险：哪天 getDesc() 变成普通 getter，载荷必须被挡在 SQL 之前
        PageRequest rawPayloadDesc = new PageRequest() {
            @Override
            public String getDesc() {
                return "desc, (SELECT usename FROM pg_user)";
            }
        };
        rawPayloadDesc.setOrder("uid");
        assertThrows(IllegalArgumentException.class,
                () -> SqlSortGuard.guard(rawPayloadDesc, MyUserBaseDb.class, "rname", "gname"));
    }

    @Test
    void order_qualifiedName_shouldBeRejected() {
        // 只允许裸列名，避免拼进任意 schema / table 名
        assertRejected(page("pg_catalog.pg_user.usename", null, "asc"));
    }

    @Test
    void order_nonSelectedColumn_shouldBeRejected() {
        // upwd 是 @SqlColumn(selectEnable=false)，密码哈希不允许当排序键
        assertRejected(page("upwd", null, "asc"));
    }

    @Test
    void order_beanIgnoredColumn_shouldBeRejected() {
        // iid/sid 在 @SqlBean(ignores=...) 里，userbase 表没有这两列；
        // 拼进 ORDER BY 会因联表歧义报错，并被框架静默吞成「成功但空列表」
        assertRejected(page("iid", null, "asc"));
        assertRejected(page("sid", null, "asc"));
    }

    @Test
    void order_tooLongOrTooManyColumns_shouldBeRejected() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longName.append('a');
        }
        assertRejected(page(longName.toString(), null, "asc"));

        StringBuilder tooMany = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            tooMany.append(i > 0 ? "," : "").append("uid");
        }
        assertRejected(page(tooMany.toString(), null, "asc"));
    }

    @Test
    void order_joinAlias_requiresExplicitAllowList() {
        // 别名列必须由调用方显式放行，不放行就拒
        assertThrows(IllegalArgumentException.class,
                () -> SqlSortGuard.guard(page("rname", null, "asc"), MyUserBaseDb.class));
    }

    // ---------- 合法输入不能被误伤 ----------

    @Test
    void order_groupAndDesc_shouldPassAndBeNormalized() {
        PageRequest pageRequest = page("createtime", "gid", "DESC");
        SqlSortGuard.guard(pageRequest, MyUserBaseDb.class, "rname", "gname");
        assertEquals("createtime", pageRequest.getOrder());
        assertEquals("gid", pageRequest.getGroup());
        assertEquals("desc", pageRequest.getDesc());
    }

    @Test
    void order_multipleColumns_shouldPassWithWhitespaceCollapsed() {
        PageRequest pageRequest = page("  uid , uname ", null, null);
        SqlSortGuard.guard(pageRequest, MyUserBaseDb.class, "rname", "gname");
        assertEquals("uid,uname", pageRequest.getOrder());
    }

    @Test
    void order_upperCase_shouldBeLowered() {
        PageRequest pageRequest = page("CREATETIME", null, null);
        SqlSortGuard.guard(pageRequest, MyUserBaseDb.class);
        assertEquals("createtime", pageRequest.getOrder());
    }

    @Test
    void nullPageRequestAndBlankValues_shouldNotThrow() {
        SqlSortGuard.guard(null, MyUserBaseDb.class);

        PageRequest blank = page("   ", "", null);
        SqlSortGuard.guard(blank, MyUserBaseDb.class);
        assertNull(blank.getOrder());
        assertNull(blank.getGroup());
        // 框架 getter 会把缺省的 desc 归一成 "asc"（构造器默认值也是 asc），不会返回 null
        assertEquals("asc", blank.getDesc());
    }

    @Test
    void order_plainColumn_shouldWorkWithoutExtras() {
        PageRequest pageRequest = page("uaccess", null, "asc");
        SqlSortGuard.guard(pageRequest, MyUserBaseDb.class);
        assertEquals("uaccess", pageRequest.getOrder());
    }
}
