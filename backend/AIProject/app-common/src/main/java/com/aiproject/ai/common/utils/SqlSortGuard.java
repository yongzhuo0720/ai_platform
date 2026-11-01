package com.aiproject.ai.common.utils;

import blxt.qjava.autovalue.inter.SqlBean;
import blxt.qjava.autovalue.inter.SqlColumn;
import com.heneng.hnsp.common.db.utils.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 分页排序 / 分组字段的服务端白名单校验。
 *
 * <h3>为什么必须做</h3>
 * 私有框架拼 SQL 时把 {@link PageRequest} 的 {@code order}、{@code group}、{@code desc}
 * <b>原样字符串拼接</b>进语句尾部（{@code SqlFieldTools.getPackageSql}：
 * {@code " \n ORDER by" + order + " " + desc + " NULLS last"}），既不是绑定参数也不做标识符校验。
 * 而这几个字段是通过 {@code @RequestBody PageRequest} 直接从请求体反序列化来的，
 * 等于任意登录用户都能往 ORDER BY 里塞一段 SQL。
 * 实测（2026-11-01，本机）：向 {@code /ai/myuser/query} 传
 * {@code order=1/(CASE WHEN 1=1 THEN 0 ELSE 1 END)}，服务端日志里生成的 SQL
 * 为该表达式原样落进 {@code ORDER by}，数据库回报「除以零」——表达式真的被执行了。
 * 本项目的数据库账号是超级用户，所以这条通道可以进一步被用来盲读服务器文件。
 *
 * <h3>为什么修在这里而不是框架里</h3>
 * 拼接发生在 {@code hnsp-public-common-*} 的 jar 里，项目侧改不到；而 Holder 是本项目的
 * 统一鉴权边界（外部输入都先经过它），在这里收口能盖住当前和以后所有走 Holder 的查询入口。
 *
 * <h3>规则</h3>
 * <ul>
 *   <li>{@code order}/{@code group} 按逗号拆分，每段必须匹配 {@code ^[a-z][a-z0-9_]{0,62}$}，
 *       且必须在「该 Bean 真实参与 SELECT 的列」白名单内（白名单由
 *       {@link SqlBean}/{@link SqlColumn} 注解反射得到，含 {@code keyId}，排除
 *       {@code ignores} 与 {@code selectEnable=false} 的列——后者如 {@code upwd}，
 *       不允许成为排序键，避免通过排序结果反推哈希值）。</li>
 *   <li>SELECT 里的联表别名列（如 {@code rname}/{@code gname}）由调用方显式追加。</li>
 *   <li>{@code desc} 只允许 {@code asc|desc}。注意这一条今天<b>打不断</b>：框架的
 *       {@code PageRequest.getDesc()} 不是普通 getter，而是
 *       {@code desc == null || desc.startsWith("asc") ? "asc" : "desc"}，
 *       任何载荷都会被折叠成这两个关键字之一，所以 {@code desc} 通道实际不可注入。
 *       这里保留校验是给自己上保险——本项目吃过私有依赖版本漂移的亏（pom.xml 里
 *       mybatis 被 {@code LATEST} 顶掉那次），框架哪天把它改成普通 getter，
 *       这个 tripwire 会立刻把载荷挡在 SQL 之前，而不是等日志里出现「除以零」。</li>
 *   <li>不合法就抛 {@link IllegalArgumentException}，由 {@code GlobalExceptionHandler}
 *       转成「请求参数有误」；服务端日志记录原始值，响应里不回显用户输入。</li>
 * </ul>
 *
 * <h3>顺带修掉的一个静默失败</h3>
 * 框架捕获 SQL 异常后只写日志，接口仍然返回 {@code resp_code=0 + "查询成功"} 且结果为空，
 * 所以之前只要前端传了非法排序列，页面表现就是「空列表且不报错」。改为在进入 SQL 之前显式拒绝。
 */
public final class SqlSortGuard {

    private static final Logger log = LoggerFactory.getLogger(SqlSortGuard.class);

    /** 合法列名形状：小写字母开头，只含小写字母/数字/下划线，最长 63（PostgreSQL 标识符上限） */
    private static final Pattern COLUMN_PATTERN = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    /**
     * 排序方向。必须带 ^ $ 锚点：{@code Matcher.matches()} 只判断「交替的某一支完整匹配」，
     * 不锚定会让 {@code "asc; DELETE FROM ..."} 这类载荷被当成合法值放过（单测实测到）。
     */
    private static final Pattern DIRECTION_PATTERN = Pattern.compile("^(asc|desc)$", Pattern.CASE_INSENSITIVE);
    private static final int MAX_TOKENS = 8;
    private static final int LOG_VALUE_MAX = 120;
    private static final String REJECT_MESSAGE = "排序或分组字段不合法";

    /** Bean 类 -> 可排序列集合。反射结果按类缓存，白名单派生自注解，不会漂移 */
    private static final Map<Class<?>, Set<String>> COLUMN_CACHE = new ConcurrentHashMap<Class<?>, Set<String>>();

    private SqlSortGuard() {
    }

    /**
     * 校验并就地规范化分页请求的排序 / 分组 / 方向字段。
     *
     * @param pageRequest          来自请求体的分页参数，可为 null（内部调用通常不传）
     * @param beanClass            被查询的 Bean 类，用于反射出真实列名
     * @param extraSortableColumns 允许额外排序的别名列（联表 SELECT 出来的计算列，如 rname）
     * @throws IllegalArgumentException 任一字段不合法
     */
    public static void guard(PageRequest pageRequest, Class<?> beanClass, String... extraSortableColumns) {
        if (pageRequest == null || beanClass == null) {
            return;
        }
        Set<String> allowed = new HashSet<String>(sortableColumns(beanClass));
        if (extraSortableColumns != null) {
            for (String extra : extraSortableColumns) {
                String normalized = normalize(extra);
                if (normalized != null) {
                    allowed.add(normalized);
                }
            }
        }
        pageRequest.setOrder(checkColumnList(pageRequest.getOrder(), allowed, "order", beanClass));
        pageRequest.setGroup(checkColumnList(pageRequest.getGroup(), allowed, "group", beanClass));
        pageRequest.setDesc(checkDirection(pageRequest.getDesc(), beanClass));
    }

    private static String checkColumnList(String raw, Set<String> allowed, String field, Class<?> beanClass) {
        String value = normalize(raw);
        if (value == null) {
            return null;
        }
        String[] tokens = value.split(",");
        if (tokens.length > MAX_TOKENS) {
            reject(raw, field, beanClass);
        }
        StringBuilder checked = new StringBuilder();
        for (String token : tokens) {
            String column = normalize(token);
            if (column == null || !COLUMN_PATTERN.matcher(column).matches() || !allowed.contains(column)) {
                reject(raw, field, beanClass);
            }
            if (checked.length() > 0) {
                checked.append(",");
            }
            checked.append(column);
        }
        return checked.toString();
    }

    private static String checkDirection(String raw, Class<?> beanClass) {
        String value = normalize(raw);
        if (value == null) {
            return null;
        }
        if (!DIRECTION_PATTERN.matcher(value).matches()) {
            reject(raw, "desc", beanClass);
        }
        // 统一成小写：框架把它直接拼在排序列后面，大写虽然也能执行，但保持单一形态便于排查
        return value.toLowerCase(Locale.ROOT);
    }

    /**
     * 反射出该 Bean 可以出现在 ORDER BY / GROUP BY 里的列名。
     */
    private static Set<String> sortableColumns(Class<?> beanClass) {
        Set<String> cached = COLUMN_CACHE.get(beanClass);
        if (cached != null) {
            return cached;
        }
        Set<String> columns = new LinkedHashSet<String>();
        Set<String> ignored = new HashSet<String>();
        SqlBean sqlBean = beanClass.getAnnotation(SqlBean.class);
        if (sqlBean != null) {
            String keyId = normalize(sqlBean.keyId());
            if (keyId != null) {
                columns.add(keyId);
            }
            String[] declaredIgnores = sqlBean.ignores();
            if (declaredIgnores != null) {
                for (String ignore : declaredIgnores) {
                    ignored.add(normalize(ignore));
                }
            }
        }
        for (Class<?> current = beanClass; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                SqlColumn column = field.getAnnotation(SqlColumn.class);
                // 没有 @SqlColumn 的是瞬态字段；selectEnable=false 的是密码这类不参与 SELECT 的列
                if (column == null || !column.selectEnable()) {
                    continue;
                }
                String declaredName = column.value();
                String name = normalize(declaredName == null || declaredName.trim().isEmpty()
                        ? field.getName() : declaredName);
                if (name != null && !ignored.contains(name)) {
                    columns.add(name);
                }
            }
        }
        Set<String> immutable = Collections.unmodifiableSet(columns);
        COLUMN_CACHE.put(beanClass, immutable);
        return immutable;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void reject(String raw, String field, Class<?> beanClass) {
        // 原始值只进服务端日志，响应里不回显，避免把用户输入反射回调用方
        log.warn("[SqlSortGuard] 拒绝非法 {} 字段, bean={}, value={}",
                field, beanClass.getSimpleName(), abbreviate(raw));
        throw new IllegalArgumentException(REJECT_MESSAGE);
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "null";
        }
        String singleLine = value.replaceAll("\\s+", " ");
        return singleLine.length() <= LOG_VALUE_MAX
                ? singleLine : singleLine.substring(0, LOG_VALUE_MAX) + "...";
    }
}
