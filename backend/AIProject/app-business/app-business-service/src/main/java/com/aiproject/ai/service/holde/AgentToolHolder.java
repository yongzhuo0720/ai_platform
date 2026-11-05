package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.AgentBean;
import com.aiproject.ai.service.bean.AgentToolBean;
import com.aiproject.ai.service.mapper.AgentBeanMapper;
import com.aiproject.ai.service.mapper.AgentToolBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent 工具注册的业务入口。
 *
 * <p>两道校验不能省：① 工具必须挂在当前用户自己的 Agent 下；
 * ② 工具必须带 JSON Schema，否则执行阶段无法校验模型生成的参数。</p>
 */
@Slf4j
@Service
public class AgentToolHolder {

    static final int MAX_NAME_CHARS = 100;
    static final int MAX_SCHEMA_CHARS = 20000;
    private static final Set<String> TOOL_TYPES = new HashSet<String>(
            Arrays.asList("kb_search", "http", "builtin"));

    @Autowired
    AgentToolBeanMapper<AgentToolBean> toolMapper;

    @Autowired
    AgentBeanMapper<AgentBean> configMapper;

    public Result<List<AgentToolBean>> list(String uid, Long agentIid) {
        if (AgentConfigHolder.blank(uid)) {
            return Result.failed(null, "未登录或登录已过期");
        }
        if (agentIid == null || agentIid <= 0) {
            return Result.failed(null, "缺少 Agent 主键");
        }
        if (!agentOwned(uid, agentIid)) {
            return Result.failed(null, "Agent 不存在或不属于当前用户");
        }
        AgentToolBean where = new AgentToolBean();
        where.setAgentIid(agentIid);
        where.setLastuser(uid);
        PageRequest page = new PageRequest(1, 200);
        page.setOrder("iid");
        page.setDesc("asc");
        List<AgentToolBean> list = toolMapper.select(page, where);
        return Result.succeed(list == null ? new ArrayList<AgentToolBean>() : list, "查询成功");
    }

    public Result<AgentToolBean> save(String uid, AgentToolBean data) {
        if (AgentConfigHolder.blank(uid)) {
            return Result.failed(null, "未登录或登录已过期");
        }
        if (data == null || data.getAgentIid() == null || data.getAgentIid() <= 0) {
            return Result.failed(null, "缺少 Agent 主键");
        }
        if (AgentConfigHolder.blank(data.getName())) {
            return Result.failed(null, "工具名称不能为空");
        }
        if (data.getName().trim().length() > MAX_NAME_CHARS) {
            return Result.failed(null, "工具名称最多 " + MAX_NAME_CHARS + " 个字符");
        }
        // 没有 Schema 就不能校验模型生成的参数，等于把工具裸奔出去
        if (AgentConfigHolder.blank(data.getJsonSchema())) {
            return Result.failed(null, "工具参数 Schema 不能为空");
        }
        if (data.getJsonSchema().length() > MAX_SCHEMA_CHARS) {
            return Result.failed(null, "工具参数 Schema 最多 " + MAX_SCHEMA_CHARS + " 个字符");
        }
        String toolType = AgentConfigHolder.blank(data.getToolType()) ? "builtin" : data.getToolType().trim();
        if (!TOOL_TYPES.contains(toolType)) {
            return Result.failed(null, "不支持的工具类型：" + toolType);
        }
        if (!agentOwned(uid, data.getAgentIid())) {
            return Result.failed(null, "Agent 不存在或不属于当前用户");
        }

        data.setName(data.getName().trim());
        data.setToolType(toolType);
        data.setLastuser(uid);
        if (data.getTimeoutMs() == null || data.getTimeoutMs() <= 0) {
            data.setTimeoutMs(8000);
        }
        if (data.getMaxRetries() == null || data.getMaxRetries() < 0) {
            data.setMaxRetries(0);
        }
        if (AgentConfigHolder.blank(data.getRoleiids())) {
            data.setRoleiids("[]");
        }
        if (AgentConfigHolder.blank(data.getUnitsnos())) {
            data.setUnitsnos("[]");
        }

        if (data.getIid() == null || data.getIid() <= 0) {
            data.setIid(null);
            if (toolMapper.install(data) <= 0) {
                return Result.failed(null, "保存工具失败，请稍后重试");
            }
            return Result.succeed(data, "创建成功");
        }

        // 更新：条件带 iid + 归属，且先确认这行工具确实属于本人
        AgentToolBean ownedWhere = new AgentToolBean();
        ownedWhere.setIid(data.getIid());
        ownedWhere.setLastuser(uid);
        List<AgentToolBean> owned = toolMapper.select(null, ownedWhere);
        if (owned == null || owned.isEmpty()) {
            return Result.failed(null, "工具不存在或不属于当前用户");
        }
        // 只 SET 允许修改的字段，绝不把 iid / 归属交给 update 的 SET 子句
        Long iid = data.getIid();
        AgentToolBean patch = new AgentToolBean();
        patch.setName(data.getName());
        patch.setDescription(data.getDescription());
        patch.setJsonSchema(data.getJsonSchema());
        patch.setToolType(data.getToolType());
        patch.setEndpoint(data.getEndpoint());
        patch.setTimeoutMs(data.getTimeoutMs());
        patch.setMaxRetries(data.getMaxRetries());
        patch.setRoleiids(data.getRoleiids());
        patch.setUnitsnos(data.getUnitsnos());
        patch.setAgentIid(data.getAgentIid());
        patch.setLastuser(uid);
        AgentToolBean where = new AgentToolBean();
        where.setIid(iid);
        where.setLastuser(uid);
        int rows = toolMapper.update(patch, where);
        return rows > 0 ? Result.succeed(data, "修改成功")
                : Result.failed(null, "修改失败，请稍后重试");
    }

    public Result<Boolean> delete(String uid, Long iid) {
        if (AgentConfigHolder.blank(uid)) {
            return Result.failed(false, "未登录或登录已过期");
        }
        if (iid == null || iid <= 0) {
            return Result.failed(false, "缺少工具主键");
        }
        AgentToolBean where = new AgentToolBean();
        where.setIid(iid);
        where.setLastuser(uid);
        if (toolMapper.delete(where) <= 0) {
            return Result.failed(false, "删除失败：工具不存在或不属于当前用户");
        }
        return Result.succeed(true, "删除成功");
    }

    /**
     * 供执行循环读取工具定义。与 {@link #list} 的区别：不做分页、不返回 Result，
     * 但仍带归属校验——不能把别人 Agent 的工具下发给模型。
     */
    public List<AgentToolBean> listForRun(String uid, Long agentIid) {
        if (AgentConfigHolder.blank(uid) || agentIid == null || agentIid <= 0
                || !agentOwned(uid, agentIid)) {
            return new ArrayList<AgentToolBean>();
        }
        AgentToolBean where = new AgentToolBean();
        where.setAgentIid(agentIid);
        where.setLastuser(uid);
        List<AgentToolBean> list = toolMapper.select(null, where);
        return list == null ? new ArrayList<AgentToolBean>() : list;
    }

    /** 工具必须挂在自己的 Agent 下，不能给别人的 Agent 注册工具 */
    private boolean agentOwned(String uid, Long agentIid) {
        AgentBean where = new AgentBean();
        where.setIid(agentIid);
        where.setLastuser(uid);
        List<AgentBean> list = configMapper.select(null, where);
        return list != null && !list.isEmpty();
    }
}
