package com.aiproject.ai.service.util;

import java.util.ArrayList;
import java.util.List;

import com.aiproject.ai.permission.bean.MyUserBaseDb;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 平台级目录（能力广场 / 应用构建 / 智能体助手）的「部分不可见」权限判定。
 *
 * <h3>为什么要有这个类</h3>
 * 原先这三个 Holder 把当前用户的 rid / gid 用 {@code String.format} 拼进一段
 * 原生 SQL 片段再交给框架执行：
 *
 * <pre>
 * String sql = String.format(
 *     "NOT (roleiids::jsonb @&gt; '%s') and NOT (unitsnos::jsonb @&gt; '\"%s\"')",
 *     userBaseDb.getRid(), userBaseDb.getGid());
 * mapper.select(pageRequest, data, sql);
 * </pre>
 *
 * 框架的 {@code select(PageRequest, EntityBase, String...)} 是把这些片段
 * <b>原样拼进 WHERE 子句</b>的（{@code SqlFieldTools.getSelectWhereAll} 用
 * {@code String.format("(%s)", fragment)} 拼接），并不做参数绑定；
 * 而 {@code gid} 是用户表里可被写入的字段，于是形成了 SQL 注入面。
 * 与之等价的 jsonb 包含判断完全可以放到 Java 里做，既去掉了注入面，
 * 也不必再依赖 {@code ::jsonb} 强转（该强转遇到非 JSON 的历史数据会直接 22P02 报错）。
 *
 * <h3>语义</h3>
 * 本类只负责「黑名单」判定，与知识库 {@code KnowledgeBaseBeanHolder.isVisibleToUser}
 * 的 {@code partial_invisible} 分支保持一致：
 * <ul>
 *   <li>{@code roleiids} 命中当前用户 rid → 不可见</li>
 *   <li>{@code unitsnos} 命中当前用户 gid → 不可见</li>
 *   <li>两个字段为空 / 为 {@code []} → 可见</li>
 *   <li>字段内容无法解析 → <b>不可见</b>（fail-closed，避免「配错了反而全放开」）</li>
 * </ul>
 */
public final class ResourceVisibility {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ResourceVisibility() {
    }

    /**
     * 判断一条平台级目录资源对当前用户是否可见。
     *
     * @param roleiids 资源上的角色黑名单（JSON 数组文本，如 {@code [1]} 或 {@code ["1"]}），可为 null
     * @param unitsnos 资源上的部门黑名单（JSON 数组文本，如 {@code ["1111"]}），可为 null
     * @param user     当前用户，要求 rid 非空（调用方已保证）
     * @return true 表示可见
     */
    public static boolean isVisible(String roleiids, String unitsnos, MyUserBaseDb user) {
        if (user == null || user.getRid() == null) {
            return false;
        }
        // 角色黑名单：DB 里可能是数字数组或字符串数组，统一按字符串比较
        List<String> roles = parseArray(roleiids);
        if (roles == null) {
            return false;
        }
        if (roles.contains(String.valueOf(user.getRid()))) {
            return false;
        }
        // 部门黑名单
        if (unitsnos != null && !unitsnos.trim().isEmpty()) {
            List<String> units = parseArray(unitsnos);
            if (units == null) {
                return false;
            }
            String gid = user.getGid();
            if (gid != null && !gid.isEmpty() && units.contains(gid)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析 JSON 数组文本。空值按「空数组」处理。
     *
     * @return 解析结果；无法解析时返回 {@code null}，由调用方按不可见处理
     */
    private static List<String> parseArray(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<Object> raw = OBJECT_MAPPER.readValue(json, new TypeReference<List<Object>>() {
            });
            List<String> result = new ArrayList<>(raw.size());
            for (Object item : raw) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }
}
