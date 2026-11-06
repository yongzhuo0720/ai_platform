package com.aiproject.ai.service.search;

import com.aiproject.ai.service.bean.AgentBean;
import com.aiproject.ai.service.bean.DatasetsBean;
import com.aiproject.ai.service.bean.KnowledgeBaseBean;
import com.aiproject.ai.service.bean.ModelsBean;
import com.aiproject.ai.service.bean.RagEvalDatasetBean;
import com.aiproject.ai.service.holde.AgentConfigHolder;
import com.aiproject.ai.service.holde.DatasetsBeanHolder;
import com.aiproject.ai.service.holde.KnowledgeBaseBeanHolder;
import com.aiproject.ai.service.holde.ModelsBeanHolder;
import com.aiproject.ai.service.holde.RagEvalHolder;
import com.aiproject.ai.service.search.dto.GlobalSearchGroup;
import com.aiproject.ai.service.search.dto.GlobalSearchItem;
import com.aiproject.ai.service.search.dto.GlobalSearchRequest;
import com.aiproject.ai.service.search.dto.GlobalSearchResponse;
import com.heneng.hnsp.common.croe.model.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 全局搜索聚合层。
 *
 * <p>命令面板只发一次请求，服务端按领域分别查询并统一成「分类 + 标题 + 说明 + 路由」。
 * 权限不在这一层重新实现：Agent / 评估数据集按 {@code lastuser} 收窄，
 * 知识库复用自身的可见性判断，模型与数据集本来就是平台级公开目录。</p>
 *
 * <p>可靠性：任一数据源异常只标记 {@code degraded} 并跳过该分类，
 * 不让一个慢查询把整个命令面板拖垮。每个分类最多返回 6 条，
 * 避免输入单个字符时把整张表塞进响应。</p>
 */
@Slf4j
@Service
public class GlobalSearchHolder {

    static final int MAX_KEYWORD_LENGTH = 50;
    static final int PER_CATEGORY_LIMIT = 6;
    private static final int SUBTITLE_LIMIT = 120;

    @Autowired
    AgentConfigHolder agentConfigHolder;

    @Autowired
    KnowledgeBaseBeanHolder knowledgeBaseBeanHolder;

    @Autowired
    RagEvalHolder ragEvalHolder;

    @Autowired
    ModelsBeanHolder modelsBeanHolder;

    @Autowired
    DatasetsBeanHolder datasetsBeanHolder;

    public Result<GlobalSearchResponse> search(String uid, GlobalSearchRequest request) {
        if (uid == null || uid.trim().isEmpty()) {
            return Result.failed(null, "未登录或登录已过期");
        }
        if (request == null) {
            return Result.failed(null, "缺少搜索条件");
        }
        String keyword = request.getKeyword() == null ? "" : request.getKeyword().trim();
        if (keyword.length() > MAX_KEYWORD_LENGTH) {
            return Result.failed(null, "关键词最多 " + MAX_KEYWORD_LENGTH + " 个字符");
        }

        GlobalSearchResponse response = new GlobalSearchResponse();
        response.setKeyword(keyword);
        if (keyword.isEmpty()) {
            return Result.succeed(response, "请输入关键词");
        }

        boolean degraded = false;

        List<GlobalSearchItem> agentItems = new ArrayList<GlobalSearchItem>();
        try {
            for (AgentBean row : agentConfigHolder.search(uid, keyword, PER_CATEGORY_LIMIT)) {
                agentItems.add(agentItem(row));
            }
        } catch (Exception e) {
            degraded = true;
            log.warn("[GlobalSearch] Agent 检索失败, uid={}: {}", uid, e.getMessage());
        }

        List<GlobalSearchItem> kbItems = new ArrayList<GlobalSearchItem>();
        try {
            for (KnowledgeBaseBean row : knowledgeBaseBeanHolder.searchVisible(uid, keyword, PER_CATEGORY_LIMIT)) {
                kbItems.add(knowledgeBaseItem(uid, row));
            }
        } catch (Exception e) {
            degraded = true;
            log.warn("[GlobalSearch] 知识库检索失败, uid={}: {}", uid, e.getMessage());
        }

        List<GlobalSearchItem> ragItems = new ArrayList<GlobalSearchItem>();
        try {
            for (RagEvalDatasetBean row : ragEvalHolder.searchDatasets(uid, keyword, PER_CATEGORY_LIMIT)) {
                ragItems.add(ragDatasetItem(row));
            }
        } catch (Exception e) {
            degraded = true;
            log.warn("[GlobalSearch] RAG 数据集检索失败, uid={}: {}", uid, e.getMessage());
        }

        List<GlobalSearchItem> modelItems = new ArrayList<GlobalSearchItem>();
        try {
            for (ModelsBean row : modelsBeanHolder.search(keyword, PER_CATEGORY_LIMIT)) {
                modelItems.add(modelItem(row));
            }
        } catch (Exception e) {
            degraded = true;
            log.warn("[GlobalSearch] 模型检索失败, uid={}: {}", uid, e.getMessage());
        }

        List<GlobalSearchItem> datasetItems = new ArrayList<GlobalSearchItem>();
        try {
            for (DatasetsBean row : datasetsBeanHolder.search(keyword, PER_CATEGORY_LIMIT)) {
                datasetItems.add(datasetItem(row));
            }
        } catch (Exception e) {
            degraded = true;
            log.warn("[GlobalSearch] 数据集检索失败, uid={}: {}", uid, e.getMessage());
        }

        int total = 0;
        total += addGroup(response, "agent", "Agent", agentItems);
        total += addGroup(response, "knowledge-base", "知识库", kbItems);
        total += addGroup(response, "rag-dataset", "RAG 评估", ragItems);
        total += addGroup(response, "model", "模型", modelItems);
        total += addGroup(response, "dataset", "数据集", datasetItems);
        response.setTotal(total);
        response.setDegraded(degraded);
        return Result.succeed(response, "搜索成功");
    }

    private int addGroup(GlobalSearchResponse response, String category, String label,
                         List<GlobalSearchItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        response.getGroups().add(new GlobalSearchGroup(category, label, items));
        return items.size();
    }

    private GlobalSearchItem agentItem(AgentBean row) {
        GlobalSearchItem item = new GlobalSearchItem();
        item.setCategory("agent");
        item.setTitle(orDefault(row.getName(), "未命名 Agent"));
        item.setSubtitle(truncate(orDefault(row.getDescription(), "私有 Agent 配置")));
        item.setRoute(withQuery("/agents", "agentKey", row.getAgentKey()));
        return item;
    }

    private GlobalSearchItem knowledgeBaseItem(String uid, KnowledgeBaseBean row) {
        boolean own = uid.equals(row.getOwnerUid());
        String scope = own ? "我的知识库"
                : ("public".equals(row.getPermissionType()) ? "公开知识库" : "共享知识库");
        GlobalSearchItem item = new GlobalSearchItem();
        item.setCategory("knowledge-base");
        item.setTitle(orDefault(row.getName(), orDefault(row.getKbId(), "未命名知识库")));
        item.setSubtitle(truncate(join(scope, row.getKbId())));
        item.setRoute(withQuery("/knowledge-bases", "keyword",
                orDefault(row.getName(), row.getKbId())));
        return item;
    }

    private GlobalSearchItem ragDatasetItem(RagEvalDatasetBean row) {
        String cases = row.getCaseCount() == null ? null : row.getCaseCount() + " 条用例";
        GlobalSearchItem item = new GlobalSearchItem();
        item.setCategory("rag-dataset");
        item.setTitle(orDefault(row.getName(), "未命名评估数据集"));
        item.setSubtitle(truncate(join("RAG 评估", row.getKbName(), cases,
                row.getDescription())));
        item.setRoute(withQuery("/rag-eval", "datasetKey", row.getDatasetKey()));
        return item;
    }

    private GlobalSearchItem modelItem(ModelsBean row) {
        GlobalSearchItem item = new GlobalSearchItem();
        item.setCategory("model");
        item.setTitle(orDefault(row.getName(), "未命名模型"));
        item.setSubtitle(truncate(join(row.getProvider(), row.getCategory(),
                row.getDescription())));
        item.setRoute(withQuery("/models", "keyword", row.getName()));
        return item;
    }

    private GlobalSearchItem datasetItem(DatasetsBean row) {
        GlobalSearchItem item = new GlobalSearchItem();
        item.setCategory("dataset");
        item.setTitle(orDefault(row.getName(), "未命名数据集"));
        item.setSubtitle(truncate(join(row.getType(), row.getSize(),
                row.getDescription())));
        item.setRoute(withQuery("/datasets", "keyword", row.getName()));
        return item;
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(part.trim());
        }
        return sb.length() == 0 ? "点击打开" : sb.toString();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= SUBTITLE_LIMIT) {
            return value;
        }
        return value.substring(0, SUBTITLE_LIMIT) + "…";
    }

    private static String withQuery(String path, String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            return path;
        }
        return path + "?" + name + "=" + encode(value.trim());
    }

    private static String encode(String value) {
        try {
            // URLEncoder 把空格编码成 +，而前端路由 query 期望 %20
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            log.warn("[GlobalSearch] 关键词编码失败: {}", e.getMessage());
            return "";
        }
    }
}
