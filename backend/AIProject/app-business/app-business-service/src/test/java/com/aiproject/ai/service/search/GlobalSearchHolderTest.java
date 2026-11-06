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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 全局搜索聚合层测试。
 *
 * <p>重点不是「能查到」，而是三件容易出事故的事：
 * 未登录/空关键词不打数据库；单个数据源异常不能拖垮整个面板；
 * 跳转路径里的关键词必须正确编码。</p>
 */
@ExtendWith(MockitoExtension.class)
class GlobalSearchHolderTest {

    private static final String UID = "u1";
    private static final String KEYWORD = "制度";

    @Mock
    AgentConfigHolder agentConfigHolder;

    @Mock
    KnowledgeBaseBeanHolder knowledgeBaseBeanHolder;

    @Mock
    RagEvalHolder ragEvalHolder;

    @Mock
    ModelsBeanHolder modelsBeanHolder;

    @Mock
    DatasetsBeanHolder datasetsBeanHolder;

    @InjectMocks
    GlobalSearchHolder holder;

    private GlobalSearchRequest request(String keyword) {
        GlobalSearchRequest request = new GlobalSearchRequest();
        request.setKeyword(keyword);
        return request;
    }

    private AgentBean agent(String key, String name, String description) {
        AgentBean bean = new AgentBean();
        bean.setAgentKey(key);
        bean.setName(name);
        bean.setDescription(description);
        return bean;
    }

    private KnowledgeBaseBean kb(String kbId, String name, String owner, String permission) {
        KnowledgeBaseBean bean = new KnowledgeBaseBean();
        bean.setKbId(kbId);
        bean.setName(name);
        bean.setOwnerUid(owner);
        bean.setPermissionType(permission);
        return bean;
    }

    private RagEvalDatasetBean rag(String key, String name, String kbName, Integer cases) {
        RagEvalDatasetBean bean = new RagEvalDatasetBean();
        bean.setDatasetKey(key);
        bean.setName(name);
        bean.setKbName(kbName);
        bean.setCaseCount(cases);
        return bean;
    }

    private ModelsBean model(String name) {
        ModelsBean bean = new ModelsBean();
        bean.setName(name);
        bean.setProvider("SiliconFlow");
        return bean;
    }

    private DatasetsBean dataset(String name) {
        DatasetsBean bean = new DatasetsBean();
        bean.setName(name);
        bean.setType("文本");
        return bean;
    }

    private GlobalSearchGroup group(GlobalSearchResponse response, String category) {
        return response.getGroups().stream()
                .filter(g -> category.equals(g.getCategory()))
                .findFirst()
                .orElse(null);
    }

    @Test
    void search_shouldRejectMissingLogin() {
        Result<GlobalSearchResponse> result = holder.search("  ", request(KEYWORD));

        assertNull(result.getDatas());
        assertEquals("未登录或登录已过期", result.getResp_msg());
        verify(agentConfigHolder, never()).search(anyString(), anyString(), anyInt());
    }

    @Test
    void search_shouldRejectMissingRequest() {
        Result<GlobalSearchResponse> result = holder.search(UID, null);

        assertNull(result.getDatas());
        assertEquals("缺少搜索条件", result.getResp_msg());
    }

    @Test
    void search_blankKeyword_shouldNotQuerySources() {
        Result<GlobalSearchResponse> result = holder.search(UID, request("   "));

        assertEquals(0, result.getDatas().getTotal());
        assertFalse(result.getDatas().isDegraded());
        assertTrue(result.getDatas().getGroups().isEmpty());
        verify(modelsBeanHolder, never()).search(anyString(), anyInt());
        verify(knowledgeBaseBeanHolder, never()).searchVisible(anyString(), anyString(), anyInt());
    }

    @Test
    void search_tooLongKeyword_shouldFailBeforeQuerying() {
        StringBuilder longKeyword = new StringBuilder();
        for (int i = 0; i < GlobalSearchHolder.MAX_KEYWORD_LENGTH + 1; i++) {
            longKeyword.append('a');
        }

        Result<GlobalSearchResponse> result = holder.search(UID, request(longKeyword.toString()));

        assertNull(result.getDatas());
        assertTrue(result.getResp_msg().contains("最多"));
        verify(agentConfigHolder, never()).search(anyString(), anyString(), anyInt());
    }

    @Test
    void search_shouldAggregateGroupsInStableOrder() {
        when(agentConfigHolder.search(UID, KEYWORD, GlobalSearchHolder.PER_CATEGORY_LIMIT))
                .thenReturn(Collections.singletonList(agent("agent-1", "制度 Agent", "查制度")));
        when(knowledgeBaseBeanHolder.searchVisible(UID, KEYWORD, GlobalSearchHolder.PER_CATEGORY_LIMIT))
                .thenReturn(Collections.singletonList(kb("kb-1", "制度库", UID, "only_me")));
        when(ragEvalHolder.searchDatasets(UID, KEYWORD, GlobalSearchHolder.PER_CATEGORY_LIMIT))
                .thenReturn(Collections.singletonList(rag("ds-1", "制度评估", "制度库", 8)));
        when(modelsBeanHolder.search(KEYWORD, GlobalSearchHolder.PER_CATEGORY_LIMIT))
                .thenReturn(Collections.singletonList(model("视觉 模型")));
        when(datasetsBeanHolder.search(KEYWORD, GlobalSearchHolder.PER_CATEGORY_LIMIT))
                .thenReturn(Collections.singletonList(dataset("制度数据集")));

        Result<GlobalSearchResponse> result = holder.search(UID, request(KEYWORD));
        GlobalSearchResponse response = result.getDatas();

        assertEquals(5, response.getTotal());
        assertFalse(response.isDegraded());
        assertEquals(Arrays.asList("agent", "knowledge-base", "rag-dataset", "model", "dataset"),
                Arrays.asList(response.getGroups().get(0).getCategory(),
                        response.getGroups().get(1).getCategory(),
                        response.getGroups().get(2).getCategory(),
                        response.getGroups().get(3).getCategory(),
                        response.getGroups().get(4).getCategory()));

        assertEquals("/agents?agentKey=agent-1",
                group(response, "agent").getItems().get(0).getRoute());
        assertEquals("/knowledge-bases?keyword=%E5%88%B6%E5%BA%A6%E5%BA%93",
                group(response, "knowledge-base").getItems().get(0).getRoute());
        assertEquals("/rag-eval?datasetKey=ds-1",
                group(response, "rag-dataset").getItems().get(0).getRoute());
        // 空格必须编码为 %20，不能是 URLEncoder 默认的 +
        assertEquals("/models?keyword=%E8%A7%86%E8%A7%89%20%E6%A8%A1%E5%9E%8B",
                group(response, "model").getItems().get(0).getRoute());
        assertEquals("我的知识库 · kb-1",
                group(response, "knowledge-base").getItems().get(0).getSubtitle());
        assertEquals("SiliconFlow", group(response, "model").getItems().get(0).getSubtitle());
        assertEquals("文本", group(response, "dataset").getItems().get(0).getSubtitle());
    }

    @Test
    void search_sourceFailure_shouldDegradeInsteadOfFailingWholeRequest() {
        when(agentConfigHolder.search(UID, KEYWORD, GlobalSearchHolder.PER_CATEGORY_LIMIT))
                .thenThrow(new RuntimeException("db down"));
        when(knowledgeBaseBeanHolder.searchVisible(UID, KEYWORD, GlobalSearchHolder.PER_CATEGORY_LIMIT))
                .thenReturn(Collections.singletonList(kb("kb-1", "制度库", "u2", "public")));
        when(ragEvalHolder.searchDatasets(UID, KEYWORD, GlobalSearchHolder.PER_CATEGORY_LIMIT))
                .thenReturn(Collections.<RagEvalDatasetBean>emptyList());
        when(modelsBeanHolder.search(KEYWORD, GlobalSearchHolder.PER_CATEGORY_LIMIT))
                .thenReturn(Collections.<ModelsBean>emptyList());
        when(datasetsBeanHolder.search(KEYWORD, GlobalSearchHolder.PER_CATEGORY_LIMIT))
                .thenReturn(Collections.<DatasetsBean>emptyList());

        Result<GlobalSearchResponse> result = holder.search(UID, request(KEYWORD));

        assertTrue(result.getDatas().isDegraded());
        assertEquals(1, result.getDatas().getTotal());
        assertNull(group(result.getDatas(), "agent"));
        assertEquals("公开知识库 · kb-1",
                group(result.getDatas(), "knowledge-base").getItems().get(0).getSubtitle());
    }

    @Test
    void search_missingOptionalFields_shouldUseFallbacks() {
        when(agentConfigHolder.search(UID, KEYWORD, GlobalSearchHolder.PER_CATEGORY_LIMIT))
                .thenReturn(Collections.singletonList(agent(null, null, null)));
        when(knowledgeBaseBeanHolder.searchVisible(UID, KEYWORD, GlobalSearchHolder.PER_CATEGORY_LIMIT))
                .thenReturn(Collections.<KnowledgeBaseBean>emptyList());
        when(ragEvalHolder.searchDatasets(UID, KEYWORD, GlobalSearchHolder.PER_CATEGORY_LIMIT))
                .thenReturn(Collections.<RagEvalDatasetBean>emptyList());
        when(modelsBeanHolder.search(KEYWORD, GlobalSearchHolder.PER_CATEGORY_LIMIT))
                .thenReturn(Collections.<ModelsBean>emptyList());
        when(datasetsBeanHolder.search(KEYWORD, GlobalSearchHolder.PER_CATEGORY_LIMIT))
                .thenReturn(Collections.<DatasetsBean>emptyList());

        GlobalSearchItem item = holder.search(UID, request(KEYWORD))
                .getDatas().getGroups().get(0).getItems().get(0);

        assertEquals("未命名 Agent", item.getTitle());
        assertEquals("私有 Agent 配置", item.getSubtitle());
        // agentKey 为空时不能拼出 "?agentKey="
        assertEquals("/agents", item.getRoute());
    }

    @Test
    void search_shouldPassKeywordAndLimitToEverySource() {
        when(agentConfigHolder.search(anyString(), anyString(), anyInt()))
                .thenReturn(Collections.<AgentBean>emptyList());
        when(knowledgeBaseBeanHolder.searchVisible(anyString(), anyString(), anyInt()))
                .thenReturn(Collections.<KnowledgeBaseBean>emptyList());
        when(ragEvalHolder.searchDatasets(anyString(), anyString(), anyInt()))
                .thenReturn(Collections.<RagEvalDatasetBean>emptyList());
        when(modelsBeanHolder.search(anyString(), anyInt()))
                .thenReturn(Collections.<ModelsBean>emptyList());
        when(datasetsBeanHolder.search(anyString(), anyInt()))
                .thenReturn(Collections.<DatasetsBean>emptyList());

        holder.search(UID, request("  " + KEYWORD + "  "));

        verify(agentConfigHolder).search(UID, KEYWORD, GlobalSearchHolder.PER_CATEGORY_LIMIT);
        verify(knowledgeBaseBeanHolder).searchVisible(UID, KEYWORD, GlobalSearchHolder.PER_CATEGORY_LIMIT);
        verify(ragEvalHolder).searchDatasets(UID, KEYWORD, GlobalSearchHolder.PER_CATEGORY_LIMIT);
        verify(modelsBeanHolder).search(KEYWORD, GlobalSearchHolder.PER_CATEGORY_LIMIT);
        verify(datasetsBeanHolder).search(KEYWORD, GlobalSearchHolder.PER_CATEGORY_LIMIT);
    }
}
