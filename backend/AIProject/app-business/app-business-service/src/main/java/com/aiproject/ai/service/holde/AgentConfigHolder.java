package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.AgentBean;
import com.aiproject.ai.service.mapper.AgentBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Agent 配置的业务入口。
 *
 * <p>权限：配置是用户私有资产，所有查询与修改条件都带 {@code lastuser=当前用户}，
 * 且写操作强制覆盖 lastuser，不接受前端传入的归属。没有管理员越权入口。</p>
 *
 * <p>本子阶段只做数据层 CRUD；执行循环与工具调用在后续子阶段接入。</p>
 */
@Slf4j
@Service
public class AgentConfigHolder {

    static final int MAX_NAME_CHARS = 100;
    static final int MAX_DESC_CHARS = 2000;
    static final int MAX_PROMPT_CHARS = 20000;
    static final int DEFAULT_MAX_ITERATIONS = 8;
    static final long DEFAULT_TOKEN_BUDGET = 20000L;
    static final long DEFAULT_TOOL_TIMEOUT_MS = 8000L;
    /** 执行护栏上限：不做这层校验，前端传一个超大值就能绕开编排里的预算与超时 */
    static final int MAX_ITERATIONS_LIMIT = 20;
    static final long TOKEN_BUDGET_LIMIT = 1000000L;
    static final long TOOL_TIMEOUT_MIN_MS = 1000L;
    static final long TOOL_TIMEOUT_MAX_MS = 120000L;
    private static final Set<String> PERMISSION_TYPES = new HashSet<String>(
            Arrays.asList("public", "only_me", "partial_invisible"));

    @Autowired
    AgentBeanMapper<AgentBean> configMapper;

    public Result<List<AgentBean>> list(String uid) {
        if (blank(uid)) {
            return Result.failed(null, "未登录或登录已过期");
        }
        AgentBean where = new AgentBean();
        where.setLastuser(uid);
        PageRequest page = new PageRequest(1, 200);
        page.setOrder("iid");
        page.setDesc("desc");
        List<AgentBean> list = configMapper.select(page, where);
        return Result.succeed(list == null ? new ArrayList<AgentBean>() : list, "查询成功");
    }

    public Result<AgentBean> create(String uid, AgentBean data) {
        if (blank(uid)) {
            return Result.failed(null, "未登录或登录已过期");
        }
        if (data == null || blank(data.getName())) {
            return Result.failed(null, "Agent 名称不能为空");
        }
        String name = data.getName().trim();
        if (name.length() > MAX_NAME_CHARS) {
            return Result.failed(null, "Agent 名称最多 " + MAX_NAME_CHARS + " 个字符");
        }
        if (data.getDescription() != null && data.getDescription().length() > MAX_DESC_CHARS) {
            return Result.failed(null, "说明最多 " + MAX_DESC_CHARS + " 个字符");
        }
        if (data.getSystemPrompt() != null && data.getSystemPrompt().length() > MAX_PROMPT_CHARS) {
            return Result.failed(null, "System Prompt 最多 " + MAX_PROMPT_CHARS + " 个字符");
        }
        String settingError = validateSettings(data);
        if (settingError != null) {
            return Result.failed(null, settingError);
        }

        // install 不回填 iid，也不能信任前端传来的归属/业务键，全部由服务端重新确定
        data.setIid(null);
        data.setAgentKey(UUID.randomUUID().toString().replace("-", ""));
        data.setName(name);
        data.setLastuser(uid);
        if (data.getMaxIterations() == null || data.getMaxIterations() <= 0) {
            data.setMaxIterations(DEFAULT_MAX_ITERATIONS);
        }
        if (data.getTokenBudget() == null || data.getTokenBudget() <= 0) {
            data.setTokenBudget(DEFAULT_TOKEN_BUDGET);
        }
        if (data.getToolTimeoutMs() == null || data.getToolTimeoutMs() <= 0) {
            data.setToolTimeoutMs(DEFAULT_TOOL_TIMEOUT_MS);
        }
        if (blank(data.getPermissionType())) {
            data.setPermissionType("only_me");
        }
        if (blank(data.getRoleiids())) {
            data.setRoleiids("[]");
        }
        if (blank(data.getUnitsnos())) {
            data.setUnitsnos("[]");
        }
        if (configMapper.install(data) <= 0) {
            return Result.failed(null, "创建 Agent 失败，请稍后重试");
        }
        return Result.succeed(data, "创建成功");
    }

    public Result<Boolean> update(String uid, AgentBean data) {
        if (blank(uid)) {
            return Result.failed(false, "未登录或登录已过期");
        }
        // 拒绝空条件：缺 iid 的 update 会命中整张表
        if (data == null || data.getIid() == null || data.getIid() <= 0) {
            return Result.failed(false, "缺少 Agent 主键");
        }
        if (data.getName() != null && data.getName().trim().isEmpty()) {
            return Result.failed(false, "Agent 名称不能为空");
        }
        if (data.getName() != null && data.getName().trim().length() > MAX_NAME_CHARS) {
            return Result.failed(false, "Agent 名称最多 " + MAX_NAME_CHARS + " 个字符");
        }
        if (data.getDescription() != null && data.getDescription().length() > MAX_DESC_CHARS) {
            return Result.failed(false, "说明最多 " + MAX_DESC_CHARS + " 个字符");
        }
        if (data.getSystemPrompt() != null && data.getSystemPrompt().length() > MAX_PROMPT_CHARS) {
            return Result.failed(false, "System Prompt 最多 " + MAX_PROMPT_CHARS + " 个字符");
        }
        String settingError = validateSettings(data);
        if (settingError != null) {
            return Result.failed(false, settingError);
        }
        if (!owned(uid, data.getIid())) {
            return Result.failed(false, "Agent 不存在或不属于当前用户");
        }

        // 只 SET 允许修改的字段：agentKey / lastuser / iid 不允许被改
        AgentBean patch = new AgentBean();
        patch.setName(data.getName() == null ? null : data.getName().trim());
        patch.setDescription(data.getDescription());
        patch.setSystemPrompt(data.getSystemPrompt());
        patch.setModelIid(data.getModelIid());
        patch.setMaxIterations(data.getMaxIterations());
        patch.setTokenBudget(data.getTokenBudget());
        patch.setToolTimeoutMs(data.getToolTimeoutMs());
        patch.setRoleiids(data.getRoleiids());
        patch.setUnitsnos(data.getUnitsnos());
        patch.setPermissionType(data.getPermissionType());
        patch.setLastuser(uid);

        AgentBean where = new AgentBean();
        where.setIid(data.getIid());
        where.setLastuser(uid);
        if (configMapper.update(patch, where) <= 0) {
            return Result.failed(false, "修改失败，请稍后重试");
        }
        return Result.succeed(true, "修改成功");
    }

    public Result<Boolean> delete(String uid, Long iid) {
        if (blank(uid)) {
            return Result.failed(false, "未登录或登录已过期");
        }
        if (iid == null || iid <= 0) {
            return Result.failed(false, "缺少 Agent 主键");
        }
        AgentBean where = new AgentBean();
        where.setIid(iid);
        where.setLastuser(uid);
        if (configMapper.delete(where) <= 0) {
            return Result.failed(false, "删除失败：Agent 不存在或不属于当前用户");
        }
        return Result.succeed(true, "删除成功");
    }

    /** 供执行循环按业务键定位配置，仍带归属校验 */
    public AgentBean findByKey(String uid, String agentKey) {
        if (blank(uid) || blank(agentKey)) {
            return null;
        }
        AgentBean where = new AgentBean();
        where.setAgentKey(agentKey.trim());
        where.setLastuser(uid);
        List<AgentBean> list = configMapper.select(null, where);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    /**
     * 可见性枚举与执行护栏的范围校验。
     *
     * <p>{@code permissionType} 必须走白名单：写错的值在可见性解析里会被
     * fail-closed 判成「不可见」，用户只会看到 Agent 凭空消失却不知道原因。
     * 数值护栏同理——它们不是展示用的建议值，而是编排器真正会用的上限。</p>
     */
    private String validateSettings(AgentBean data) {
        if (!blank(data.getPermissionType())
                && !PERMISSION_TYPES.contains(data.getPermissionType().trim())) {
            return "不支持的可见性类型：" + data.getPermissionType();
        }
        if (data.getMaxIterations() != null
                && (data.getMaxIterations() < 1 || data.getMaxIterations() > MAX_ITERATIONS_LIMIT)) {
            return "最大迭代步数需在 1~" + MAX_ITERATIONS_LIMIT + " 之间";
        }
        if (data.getTokenBudget() != null
                && (data.getTokenBudget() < 1 || data.getTokenBudget() > TOKEN_BUDGET_LIMIT)) {
            return "Token 预算需在 1~" + TOKEN_BUDGET_LIMIT + " 之间";
        }
        if (data.getToolTimeoutMs() != null
                && (data.getToolTimeoutMs() < TOOL_TIMEOUT_MIN_MS
                    || data.getToolTimeoutMs() > TOOL_TIMEOUT_MAX_MS)) {
            return "单工具超时需在 " + TOOL_TIMEOUT_MIN_MS + "~" + TOOL_TIMEOUT_MAX_MS + " 毫秒之间";
        }
        return null;
    }

    /**
     * 全局搜索：只在当前用户自己的 Agent 配置里匹配名称与说明。
     *
     * <p>Agent 是私有资产，查询条件必须带 {@code lastuser=uid}；
     * 不接受管理员越权，也不把 System Prompt 纳入检索范围，避免把大段提示词
     * 当搜索结果外泄到命令面板里。</p>
     */
    public List<AgentBean> search(String uid, String keyword, int limit) {
        List<AgentBean> result = new ArrayList<AgentBean>();
        if (blank(uid) || limit <= 0) {
            return result;
        }
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return result;
        }
        AgentBean where = new AgentBean();
        where.setLastuser(uid);
        PageRequest page = new PageRequest(1, 200);
        page.setOrder("iid");
        page.setDesc("desc");
        List<AgentBean> rows = configMapper.select(page, where);
        if (rows == null) {
            return result;
        }
        for (AgentBean row : rows) {
            if (row == null) {
                continue;
            }
            if (matches(needle, row.getName(), row.getDescription())) {
                result.add(row);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    private static boolean matches(String needle, String... fields) {
        for (String field : fields) {
            if (field != null && field.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean owned(String uid, Long iid) {
        AgentBean where = new AgentBean();
        where.setIid(iid);
        where.setLastuser(uid);
        List<AgentBean> list = configMapper.select(null, where);
        return list != null && !list.isEmpty();
    }

    /** 供同层 Holder 与 agent 编排包复用的空值判断 */
    public static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
