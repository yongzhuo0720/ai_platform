package com.aiproject.ai.service.holde;

import com.aiproject.ai.service.bean.KbChunkBean;
import com.aiproject.ai.service.bean.KbDocBean;
import com.aiproject.ai.service.gateway.EmbeddingClient;
import com.aiproject.ai.service.gateway.dto.KbDocUpload;
import com.aiproject.ai.service.gateway.dto.KbSearchItem;
import com.aiproject.ai.service.mapper.KbChunkBeanMapper;
import com.aiproject.ai.service.mapper.KbDocBeanMapper;
import com.heneng.hnsp.common.croe.model.Result;
import com.heneng.hnsp.common.db.utils.PageRequest;
import com.heneng.hnsp.common.db.utils.PageResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 本地知识库（RAG）归属隔离的单元测试。
 *
 * <p>这类测试针对的是一类曾经真实存在的漏洞：{@code KbRagController} 的三个接口
 * 都只校验「已登录」，不校验「资源归属」。于是任意登录用户可以：</p>
 * <ul>
 *   <li>listDocs —— 看到所有用户的文档列表（修复前用空 Bean 查全表）</li>
 *   <li>deleteDoc —— 按文档 iid 删除他人文档（修复前删除条件只有 iid）</li>
 *   <li>search    —— 检索并读取他人文档的正文分块（修复前只按 kbName 过滤）</li>
 * </ul>
 *
 * <p>断言方式统一为「捕获传给 Mapper 的查询条件 Bean，验证 lastuser 等于当前 uid」——
 * 这比验证返回结果更直接：它证明隔离发生在 SQL 层，而不是靠事后过滤。</p>
 *
 * <p>注：KbDocBean / KbChunkBean 没有独立的 ownerUid 字段，{@code lastuser}
 * （框架 DbEntityBase 自带）在这些表上就是归属字段——上传时写入且再无更新操作。</p>
 */
@ExtendWith(MockitoExtension.class)
class KbRagHolderTest {

    @Mock
    KbDocBeanMapper<KbDocBean> kbDocBeanMapper;

    @Mock
    KbChunkBeanMapper<KbChunkBean> kbChunkBeanMapper;

    @Mock
    EmbeddingClient embeddingClient;

    @InjectMocks
    KbRagHolder holder;

    private EmbeddingClient.ResolvedEmbedding embeddingConfig() {
        return new EmbeddingClient.ResolvedEmbedding(
                "https://api.example.com/v1", "sk-test", "text-embedding-3-small", "测试向量模型");
    }

    private KbChunkBean chunk(long docIid, String kbName, String content, String embedding) {
        KbChunkBean c = new KbChunkBean();
        c.setIid(docIid * 100 + 1);
        c.setDocIid(docIid);
        c.setKbName(kbName);
        c.setChunkIndex(0);
        c.setContent(content);
        c.setEmbedding(embedding);
        c.setTokenCount(content.length());
        return c;
    }

    /** 文档列表必须按归属收窄，不能拉全表 */
    @Test
    void listDocs_shouldFilterByCurrentUser() {
        when(kbDocBeanMapper.select(isNull(), any(KbDocBean.class)))
                .thenReturn(Collections.emptyList());

        holder.listDocs("u1");

        ArgumentCaptor<KbDocBean> captor = ArgumentCaptor.forClass(KbDocBean.class);
        verify(kbDocBeanMapper).select(isNull(), captor.capture());
        assertEquals("u1", captor.getValue().getLastuser(),
                "文档列表的查询条件必须带上当前用户，否则等于拉全表");
    }

    /** 未登录时直接拒绝，不发查询 */
    @Test
    void listDocs_shouldRejectWhenNotLoggedIn() {
        Result<List<KbDocBean>> result = holder.listDocs(null);
        assertNull(result.getDatas(), "未登录时不应返回任何文档");
        verify(kbDocBeanMapper, never()).select(isNull(), any(KbDocBean.class));
    }

    /** 删除他人文档：必须匹配不到行并失败，且绝不能继续删分块 */
    @Test
    void deleteDoc_shouldFailWhenDocBelongsToOthers() {
        KbDocBean data = new KbDocBean();
        data.setIid(100L);

        // 归属不匹配 → Mapper 删到 0 行
        when(kbDocBeanMapper.delete(any(KbDocBean.class))).thenReturn(0);

        Result<Boolean> result = holder.deleteDoc("u1", data);

        assertFalse(Boolean.TRUE.equals(result.getDatas()), "删他人文档必须失败");
        verify(kbChunkBeanMapper, never()).delete(any(KbChunkBean.class));
    }

    /** 删除条件必须同时含 iid 与 lastuser，缺一个就能越权 */
    @Test
    void deleteDoc_shouldScopeConditionToOwner() {
        KbDocBean data = new KbDocBean();
        data.setIid(100L);

        when(kbDocBeanMapper.delete(any(KbDocBean.class))).thenReturn(1);
        when(kbChunkBeanMapper.delete(any(KbChunkBean.class))).thenReturn(1);

        Result<Boolean> result = holder.deleteDoc("u1", data);

        assertTrue(Boolean.TRUE.equals(result.getDatas()));

        ArgumentCaptor<KbDocBean> docCaptor = ArgumentCaptor.forClass(KbDocBean.class);
        verify(kbDocBeanMapper).delete(docCaptor.capture());
        assertEquals("u1", docCaptor.getValue().getLastuser(), "删除条件必须带归属");
        assertEquals(100L, docCaptor.getValue().getIid(), "删除条件必须带主键");

        // 归属校验通过后才允许删分块
        ArgumentCaptor<KbChunkBean> chunkCaptor = ArgumentCaptor.forClass(KbChunkBean.class);
        verify(kbChunkBeanMapper).delete(chunkCaptor.capture());
        assertEquals(100L, chunkCaptor.getValue().getDocIid());
    }

    /** 检索的分块查询必须带归属，否则能读到他人文档正文 */
    @Test
    void search_shouldFilterChunksByCurrentUser() throws Exception {
        when(embeddingClient.resolve()).thenReturn(embeddingConfig());
        when(kbChunkBeanMapper.select(any(PageRequest.class), any(KbChunkBean.class)))
                .thenReturn(Collections.emptyList());

        holder.search("u1", "报销流程是什么", "默认知识库", 5);

        ArgumentCaptor<KbChunkBean> captor = ArgumentCaptor.forClass(KbChunkBean.class);
        verify(kbChunkBeanMapper).select(any(PageRequest.class), captor.capture());
        assertEquals("u1", captor.getValue().getLastuser(),
                "分块查询必须带归属，否则任意用户可检索他人文档正文");
        assertEquals("默认知识库", captor.getValue().getKbName(), "kbName 条件不应被破坏");
    }

    /** 命中结果里的「来源：文档名」同样只能来自自己的文档 */
    @Test
    void search_shouldOnlyResolveOwnDocNames() throws Exception {
        when(embeddingClient.resolve()).thenReturn(embeddingConfig());
        when(embeddingClient.embed(any(EmbeddingClient.ResolvedEmbedding.class), any(List.class)))
                .thenReturn(Collections.singletonList(new double[]{1.0, 0.0}));

        KbChunkBean own = chunk(1L, "默认知识库", "差旅报销需要部门负责人审批", "[1.0,0.0]");
        when(kbChunkBeanMapper.select(any(PageRequest.class), any(KbChunkBean.class)))
                .thenReturn(Collections.singletonList(own));

        KbDocBean doc = new KbDocBean();
        doc.setIid(1L);
        doc.setName("财务制度.txt");
        when(kbDocBeanMapper.select(isNull(), any(KbDocBean.class)))
                .thenReturn(Collections.singletonList(doc));

        Result<com.aiproject.ai.service.gateway.dto.KbSearchResult> result =
                holder.search("u1", "报销", null, 5);

        assertNotNull(result.getDatas());
        List<KbSearchItem> items = result.getDatas().getItems();
        assertEquals(1, items.size());
        assertEquals("财务制度.txt", items.get(0).getDocName());

        ArgumentCaptor<KbDocBean> captor = ArgumentCaptor.forClass(KbDocBean.class);
        verify(kbDocBeanMapper).select(isNull(), captor.capture());
        assertEquals("u1", captor.getValue().getLastuser(),
                "文档名映射也必须按归属收窄，避免把他人文档名带进响应");
    }

    /** 空知识库不需要向量化问题：否则每条消息都白调一次付费接口 */
    @Test
    void search_shouldNotEmbedQueryWhenKnowledgeBaseEmpty() throws Exception {
        when(embeddingClient.resolve()).thenReturn(embeddingConfig());
        when(kbChunkBeanMapper.select(any(PageRequest.class), any(KbChunkBean.class)))
                .thenReturn(Collections.<KbChunkBean>emptyList());

        Result<com.aiproject.ai.service.gateway.dto.KbSearchResult> result =
                holder.search("u1", "报销", null, 5);

        assertEquals("知识库为空，请先上传文档", result.getResp_msg());
        verify(embeddingClient, never()).embed(any(EmbeddingClient.ResolvedEmbedding.class), any(List.class));
    }

    /**
     * 切换向量模型后，旧分块的向量维度对不上，此前是静默跳过：
     * 语义路整段失效，用户只看到「检索变差了」。现在必须计数并给出提示。
     */
    @Test
    void search_shouldWarnWhenChunkVectorsDoNotMatchCurrentModel() throws Exception {
        when(embeddingClient.resolve()).thenReturn(embeddingConfig());
        when(embeddingClient.embed(any(EmbeddingClient.ResolvedEmbedding.class), any(List.class)))
                .thenReturn(Collections.singletonList(new double[]{1.0, 0.0}));
        KbChunkBean ok = chunk(1L, "默认知识库", "差旅报销需要审批", "[1.0,0.0]");
        KbChunkBean wrongDim = chunk(2L, "默认知识库", "报销标准为五百元", "[1.0,0.0,0.0]");
        KbChunkBean broken = chunk(3L, "默认知识库", "报销流程", "not-json");
        when(kbChunkBeanMapper.select(any(PageRequest.class), any(KbChunkBean.class)))
                .thenReturn(Arrays.asList(ok, wrongDim, broken));

        Result<com.aiproject.ai.service.gateway.dto.KbSearchResult> result =
                holder.search("u1", "报销", null, 5);

        assertNotNull(result.getDatas());
        assertEquals(2, result.getDatas().getSkipped());
        assertNotNull(result.getDatas().getWarning(), "维度不一致必须显式提示，不能静默降级");
        assertTrue(result.getDatas().getWarning().contains("重新上传"));
        assertEquals(1, result.getDatas().getItems().size(), "维度正常的块仍应参与排序");
    }

    /**
     * 换到维度相同的另一个向量模型时，旧分块（记录了旧模型名）必须被跳过：
     * 维度一样不代表语义空间一致，混用旧向量会让检索悄悄变差。
     */
    @Test
    void search_shouldSkipChunksFromAnotherEmbeddingModel() throws Exception {
        when(embeddingClient.resolve()).thenReturn(embeddingConfig());
        when(embeddingClient.embed(any(EmbeddingClient.ResolvedEmbedding.class), any(List.class)))
                .thenReturn(Collections.singletonList(new double[]{1.0, 0.0}));
        KbChunkBean current = chunk(1L, "默认知识库", "差旅报销需要审批", "[1.0,0.0]");
        current.setEmbeddingModel("text-embedding-3-small");
        current.setEmbeddingDim(2);
        KbChunkBean legacyOtherModel = chunk(2L, "默认知识库", "报销标准为五百元", "[1.0,0.0]");
        legacyOtherModel.setEmbeddingModel("bge-m3");
        legacyOtherModel.setEmbeddingDim(2);
        KbChunkBean legacyUnknown = chunk(3L, "默认知识库", "报销流程", "[0.0,1.0]");
        when(kbChunkBeanMapper.select(any(PageRequest.class), any(KbChunkBean.class)))
                .thenReturn(Arrays.asList(current, legacyOtherModel, legacyUnknown));

        Result<com.aiproject.ai.service.gateway.dto.KbSearchResult> result =
                holder.search("u1", "报销", null, 5);

        assertNotNull(result.getDatas());
        assertEquals(1, result.getDatas().getSkipped(), "其他模型的分块必须计数");
        assertEquals(2, result.getDatas().getItems().size(),
                "同模型分块与未记录模型的存量分块仍应参与排序");
        assertNotNull(result.getDatas().getWarning());
        assertTrue(result.getDatas().getWarning().contains("重新上传"));
    }

    /** 归因要分开：模型不一致、维度不一致、解析失败，处置方式不同 */
    @Test
    void hybridRank_shouldCountSkippedVectorsByReason() {
        KbChunkBean ok = chunk(1L, "kb", "报销流程", "[1.0,0.0]");
        KbChunkBean wrongDim = chunk(2L, "kb", "差旅制度", "[1.0,0.0,0.0]");
        KbChunkBean broken = chunk(3L, "kb", "其他", "not-json");
        KbRagHolder.RankDiagnostics diagnostics = new KbRagHolder.RankDiagnostics();

        List<KbSearchItem> ranked = holder.hybridRank("报销", Arrays.asList(ok, wrongDim, broken),
                new double[]{1.0, 0.0}, Collections.<Long>emptySet(), diagnostics);

        assertEquals(1, ranked.size());
        assertEquals(1, diagnostics.dimensionMismatch);
        assertEquals(1, diagnostics.unparsable);
        assertEquals(0, diagnostics.modelMismatch);
        assertEquals(2, diagnostics.skipped());
    }

    /** 未配置向量模型时直接失败，不发任何查询 */
    @Test
    void search_shouldFailWhenEmbeddingNotConfigured() {
        when(embeddingClient.resolve()).thenReturn(null);

        Result<com.aiproject.ai.service.gateway.dto.KbSearchResult> result =
                holder.search("u1", "报销", null, 5);

        assertFalse(result.getDatas() != null);
        verify(kbChunkBeanMapper, never()).select(any(PageRequest.class), any(KbChunkBean.class));
    }

    /**
     * 超大知识库不能把全部分块读进内存：超过扫描上限时只取前 N 块，
     * 并在响应里提示结果可能不完整，而不是让检索失败。
     */
    @Test
    void search_shouldCapScanAndWarnWhenKnowledgeBaseIsTooLarge() throws Exception {
        when(embeddingClient.resolve()).thenReturn(embeddingConfig());
        when(embeddingClient.embed(any(EmbeddingClient.ResolvedEmbedding.class), any(List.class)))
                .thenReturn(Collections.singletonList(new double[]{1.0, 0.0}));
        List<KbChunkBean> huge = new ArrayList<KbChunkBean>();
        for (int i = 0; i < 5001; i++) {
            huge.add(chunk(1000L + i, "默认知识库", "内容" + i, "[1.0,0.0]"));
        }
        when(kbChunkBeanMapper.select(any(PageRequest.class), any(KbChunkBean.class)))
                .thenReturn(huge);

        Result<com.aiproject.ai.service.gateway.dto.KbSearchResult> result =
                holder.search("u1", "内容", null, 5);

        assertNotNull(result.getDatas());
        assertEquals(5000, result.getDatas().getScanned(), "超出上限的部分不应读进内存");
        assertNotNull(result.getDatas().getWarning());
        assertTrue(result.getDatas().getWarning().contains("5000"));
        assertEquals(5, result.getDatas().getItems().size());

        ArgumentCaptor<PageRequest> scanCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(kbChunkBeanMapper).select(scanCaptor.capture(), any(KbChunkBean.class));
        assertEquals(Integer.valueOf(5001), scanCaptor.getValue().getPageSize(),
                "多取一条用于判断是否存在未扫描分块");
    }

    /**
     * 分块写入失败必须回收已写入的文档与分块：否则文档列表会展示
     * 一个 chunkCount 正常、实际检索不到任何内容的「空壳文档」。
     */
    @Test
    void uploadDoc_shouldRollbackWhenChunkInstallFails() throws Exception {
        when(embeddingClient.resolve()).thenReturn(embeddingConfig());
        when(embeddingClient.embed(any(EmbeddingClient.ResolvedEmbedding.class), any(List.class)))
                .thenReturn(Arrays.asList(new double[]{1.0, 0.0}, new double[]{0.0, 1.0}));
        when(kbDocBeanMapper.install(any(KbDocBean.class))).thenReturn(1);

        KbDocBean existing = new KbDocBean();
        existing.setIid(9L);
        PageResult<KbDocBean> page = new PageResult<KbDocBean>();
        page.setContent(Collections.singletonList(existing));
        when(kbDocBeanMapper.selectPage(any(PageRequest.class), any(KbDocBean.class)))
                .thenReturn(Result.succeed(page, "查询成功"));

        // 第一块成功，第二块失败
        when(kbChunkBeanMapper.install(any(KbChunkBean.class))).thenReturn(1, 0);
        when(kbChunkBeanMapper.delete(any(KbChunkBean.class))).thenReturn(2);
        when(kbDocBeanMapper.delete(any(KbDocBean.class))).thenReturn(1);

        KbDocUpload data = new KbDocUpload();
        data.setName("制度.txt");
        data.setKbName("默认知识库");
        // 超过单块上限的长段落会被滑动窗口切成两块
        data.setContent(fill('甲', 600));

        Result<KbDocBean> result = holder.uploadDoc("u1", data);

        assertNull(result.getDatas(), "分块失败时整篇文档都不应视为入库成功");
        assertNotNull(result.getResp_msg());
        assertTrue(result.getResp_msg().contains("已回滚"));

        ArgumentCaptor<KbChunkBean> chunkCaptor = ArgumentCaptor.forClass(KbChunkBean.class);
        verify(kbChunkBeanMapper).delete(chunkCaptor.capture());
        assertEquals(9L, chunkCaptor.getValue().getDocIid(), "回滚必须清掉已写入的分块");
        assertEquals("u1", chunkCaptor.getValue().getLastuser());

        ArgumentCaptor<KbDocBean> docCaptor = ArgumentCaptor.forClass(KbDocBean.class);
        verify(kbDocBeanMapper).delete(docCaptor.capture());
        assertEquals(9L, docCaptor.getValue().getIid());
        assertEquals("u1", docCaptor.getValue().getLastuser(), "回滚删除同样要带归属条件");
    }

    // ==================== 混合检索 + RRF 融合 ====================
    /**
     * 混合检索必须同时发起两路：
     * 向量路走 2 参 {@code select(pageRequest, bean)}，
     * 关键词路走 3 参 {@code select(pageRequest, bean, whereSql)}（带全文检索片段）。
     */
    @Test
    void search_shouldQueryBothChannels() throws Exception {
        KbChunkBean chunk = chunk(1L, "默认知识库", "差旅报销需要部门负责人审批", "[1.0,0.0]");
        when(embeddingClient.resolve()).thenReturn(embeddingConfig());
        when(embeddingClient.embed(any(EmbeddingClient.ResolvedEmbedding.class), any(List.class)))
                .thenReturn(Collections.singletonList(new double[]{1.0, 0.0}));
        when(kbChunkBeanMapper.select(any(PageRequest.class), any(KbChunkBean.class)))
                .thenReturn(Collections.<KbChunkBean>singletonList(chunk));
        when(kbChunkBeanMapper.select(any(PageRequest.class), any(KbChunkBean.class), anyString()))
                .thenReturn(Collections.<KbChunkBean>singletonList(chunk));

        holder.search("u1", "报销流程", "默认知识库", 5);

        verify(kbChunkBeanMapper).select(any(PageRequest.class), any(KbChunkBean.class));
        verify(kbChunkBeanMapper).select(any(PageRequest.class), any(KbChunkBean.class), anyString());
    }

    /**
     * 关键词路的查询文本必须转义，否则用户输入里的单引号会破坏 SQL。
     *
     * 向量路必须先返回非空，否则 search 会在「知识库为空」处提前返回，
     * 走不到关键词路。
     */
    @Test
    void lexicalChannel_shouldEscapeSqlLiteral() throws Exception {
        KbChunkBean chunk = chunk(1L, "默认知识库", "普通内容", "[1.0,0.0]");
        when(embeddingClient.resolve()).thenReturn(embeddingConfig());
        when(embeddingClient.embed(any(EmbeddingClient.ResolvedEmbedding.class), any(List.class)))
                .thenReturn(Collections.singletonList(new double[]{1.0, 0.0}));
        when(kbChunkBeanMapper.select(any(PageRequest.class), any(KbChunkBean.class)))
                .thenReturn(Collections.<KbChunkBean>singletonList(chunk));
        when(kbChunkBeanMapper.select(any(PageRequest.class), any(KbChunkBean.class), anyString()))
                .thenReturn(Collections.<KbChunkBean>emptyList());

        // 含单引号的恶意输入：'; DROP TABLE kbchunkbean; --
        holder.search("u1", "it's a test'; DROP TABLE kbchunkbean; --", null, 5);

        ArgumentCaptor<KbChunkBean> captor = ArgumentCaptor.forClass(KbChunkBean.class);
        verify(kbChunkBeanMapper).select(any(PageRequest.class), captor.capture(), anyString());
        for (KbChunkBean where : captor.getAllValues()) {
            assertNotNull(where);
            assertEquals("u1", where.getLastuser(), "关键词路也必须带归属条件");
        }
    }

    /**
     * 关键词路整体失败时必须降级为纯向量路，不能让检索不可用。
     */
    @Test
    void search_shouldDegradeToDenseOnly_whenLexicalFails() throws Exception {
        when(embeddingClient.resolve()).thenReturn(embeddingConfig());
        when(embeddingClient.embed(any(EmbeddingClient.ResolvedEmbedding.class), any(List.class)))
                .thenReturn(Collections.singletonList(new double[]{1.0, 0.0}));

        KbChunkBean chunk = chunk(1L, "默认知识库", "差旅报销需要部门负责人审批", "[1.0,0.0]");
        // 向量路返回分块；关键词路（3 参）抛异常
        when(kbChunkBeanMapper.select(any(PageRequest.class), any(KbChunkBean.class)))
                .thenReturn(Collections.<KbChunkBean>singletonList(chunk));
        when(kbChunkBeanMapper.select(any(PageRequest.class), any(KbChunkBean.class), anyString()))
                .thenThrow(new RuntimeException("FTS 索引不存在"));

        Result<com.aiproject.ai.service.gateway.dto.KbSearchResult> result =
                holder.search("u1", "报销", null, 5);

        assertNotNull(result.getDatas());
        assertEquals(1, result.getDatas().getItems().size(), "降级后仍应返回向量路结果");
        assertEquals("dense", result.getDatas().getItems().get(0).getChannel(),
                "关键词路失败时来源应标记为 dense");
    }

    /**
     * 两路都命中时要标注 channel=both，且两路分数都应被记录。
     */
    @Test
    void search_shouldMarkChannelBoth_whenBothChannelsHit() throws Exception {
        when(embeddingClient.resolve()).thenReturn(embeddingConfig());
        when(embeddingClient.embed(any(EmbeddingClient.ResolvedEmbedding.class), any(List.class)))
                .thenReturn(Collections.singletonList(new double[]{1.0, 0.0}));

        KbChunkBean chunk = chunk(1L, "默认知识库", "差旅报销需要部门负责人审批", "[1.0,0.0]");
        when(kbChunkBeanMapper.select(any(PageRequest.class), any(KbChunkBean.class)))
                .thenReturn(Collections.<KbChunkBean>singletonList(chunk));
        when(kbChunkBeanMapper.select(any(PageRequest.class), any(KbChunkBean.class), anyString()))
                .thenReturn(Collections.<KbChunkBean>singletonList(chunk));

        Result<com.aiproject.ai.service.gateway.dto.KbSearchResult> result =
                holder.search("u1", "报销", null, 5);

        KbSearchItem item = result.getDatas().getItems().get(0);
        assertEquals("both", item.getChannel());
        assertTrue(item.getDenseScore() >= 0, "向量路分数应被记录");
        assertTrue(item.getLexicalScore() >= 0, "关键词路分数应被记录");
    }

    /** 词项重叠统计：中文走二元滑窗，英文按整词 */
    @Test
    void countTermOverlap_shouldHandleChineseAndEnglish() {
        // 「报销」二元词项与英文 approval 都出现在正文中
        int overlap = KbRagHolder.countTermOverlap(
                "报销, approval!", "差旅报销需要审批 approval");
        assertEquals(2, overlap);
    }

    /** 词项没出现时不计分 */
    @Test
    void countTermOverlap_shouldReturnZeroWhenNoMatch() {
        assertEquals(0, KbRagHolder.countTermOverlap("股票开盘", "差旅报销需要审批"));
    }

    /** SQL 字面量转义：单引号必须加倍，否则破坏 SQL 字符串 */
    @Test
    void escapeSqlLiteral_shouldDoubleSingleQuotes() {
        assertEquals("it''s", KbRagHolder.escapeSqlLiteral("it's"));
        assertEquals("", KbRagHolder.escapeSqlLiteral(null));
        assertEquals("abc", KbRagHolder.escapeSqlLiteral("abc"));
    }

    /** 分词：中文转二元词项，英文保留整词，标点丢弃 */
    @Test
    void tokenize_shouldSplitChineseBigramsAndEnglishWords() {
        List<String> terms = KbRagHolder.tokenize("报销 approval");
        assertEquals(2, terms.size(), "中文段转成一个二元词项 + 英文整词");
        assertTrue(terms.contains("报销"));
        assertTrue(terms.contains("approval"));
    }

    /** 连续中文段按滑窗取多个二元词项：四字 → 三个重叠二元词项 */
    @Test
    void tokenize_shouldSlideOverCjkRun() {
        List<String> terms = KbRagHolder.tokenize("报销流程");
        assertEquals(3, terms.size(), "四字中文段产生三个重叠二元词项");
        assertTrue(terms.contains("报销"));
        assertTrue(terms.contains("销流"));
        assertTrue(terms.contains("流程"));
    }

    /** 单个汉字：保留单字，好过丢掉 */
    @Test
    void tokenize_shouldKeepSingleCjkChar() {
        List<String> terms = KbRagHolder.tokenize("报");
        assertEquals(1, terms.size());
        assertEquals("报", terms.get(0));
    }

    private static String fill(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }
}
