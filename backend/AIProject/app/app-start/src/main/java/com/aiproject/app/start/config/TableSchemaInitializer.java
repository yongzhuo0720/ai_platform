package com.aiproject.app.start.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 表结构初始化器。
 * <p>
 * 用于在应用启动时补全数据库表缺失的列，避免依赖人工执行 SQL。
 * 所有语句均使用 PostgreSQL 幂等语法（ADD COLUMN IF NOT EXISTS），可重复启动无副作用。
 * </p>
 */
@Slf4j
@Component
public class TableSchemaInitializer implements ApplicationListener<ApplicationReadyEvent> {

    @Autowired
    private DataSource dataSource;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        ensureCapabilitiesIconColumn();
        ensureAiGatewayIndexes();
        ensureKbChunkFullTextIndex();
        ensureKbChunkQueryIndexes();
        ensureKbChunkEmbeddingModelColumns();
        ensureKbDocPipeline();
        ensureLongTextColumns();
        ensureCostAndTracingColumns();
        ensureWeeklyReportUniqueWeek();
        ensureApiTokenTable();
        ensureControllerLogIndex();
        ensureRagEvalTables();
        ensureAgentTables();
    }

    /**
     * RAG 评估工作台的四张表与索引。
     *
     * <p>为什么不是一张大表：运行历史要能独立于数据集保留快照，
     * 逐题结果又可能有一两百行；拆开才能在列表页只查运行汇总，
     * 点开详情时再读逐题明细。</p>
     *
     * <p>活动运行唯一索引是并发闸门：同一个用户对同一数据集不能同时跑两次，
     * 否则既浪费 embedding 额度，也会让「最新一次结果」语义不明确。
     * 僵死运行由 RagEvalRunner 的定时任务回收，避免索引被永久占住。</p>
     */
    private void ensureRagEvalTables() {
        execute("CREATE TABLE IF NOT EXISTS ragevaldatasetbean ("
                + "\"datasetKey\" varchar(64), \"name\" varchar(100), \"kbName\" varchar(100), "
                + "\"description\" text, \"caseCount\" int4, \"caseSetKey\" varchar(64), "
                + "\"iid\" bigserial NOT NULL PRIMARY KEY, \"lastuser\" varchar(255), "
                + "\"createtime\" timestamp DEFAULT now(), \"updatetime\" timestamp, "
                + "\"enable\" boolean DEFAULT true, \"app\" varchar(255))");
        execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_ragevaldataset_key "
                + "ON ragevaldatasetbean (\"datasetKey\")");
        execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_ragevaldataset_owner_name "
                + "ON ragevaldatasetbean (\"lastuser\", \"name\")");

        execute("CREATE TABLE IF NOT EXISTS ragevalcasebean ("
                + "\"datasetKey\" varchar(64), \"caseSetKey\" varchar(64), \"question\" text, "
                + "\"expectedDoc\" varchar(200), \"expectedKeywords\" text, \"category\" varchar(32), "
                + "\"iid\" bigserial NOT NULL PRIMARY KEY, \"lastuser\" varchar(255), "
                + "\"createtime\" timestamp DEFAULT now(), \"updatetime\" timestamp, "
                + "\"enable\" boolean DEFAULT true, \"app\" varchar(255))");
        execute("CREATE INDEX IF NOT EXISTS idx_ragevalcase_dataset_set "
                + "ON ragevalcasebean (\"datasetKey\", \"caseSetKey\")");
        execute("CREATE INDEX IF NOT EXISTS idx_ragevalcase_user "
                + "ON ragevalcasebean (\"lastuser\")");

        execute("CREATE TABLE IF NOT EXISTS ragevalrunbean ("
                + "\"runKey\" varchar(64), \"datasetKey\" varchar(64), \"datasetName\" varchar(100), "
                + "\"kbName\" varchar(100), \"topK\" int4, \"status\" varchar(16), "
                + "\"total\" int4, \"processed\" int4, \"recall\" float8, \"precision\" float8, "
                + "\"mrr\" float8, \"ndcg\" float8, \"keywordHitRate\" float8, "
                + "\"missCount\" int4, \"keywordEvaluatedCount\" int4, "
                + "\"embeddingModel\" varchar(128), \"errorMessage\" text, "
                + "\"startedAt\" int8, \"finishedAt\" int8, \"durationMs\" int8, "
                + "\"iid\" bigserial NOT NULL PRIMARY KEY, \"lastuser\" varchar(255), "
                + "\"createtime\" timestamp DEFAULT now(), \"updatetime\" timestamp, "
                + "\"enable\" boolean DEFAULT true, \"app\" varchar(255))");
        execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_ragevalrun_key "
                + "ON ragevalrunbean (\"runKey\")");
        execute("CREATE INDEX IF NOT EXISTS idx_ragevalrun_user_dataset "
                + "ON ragevalrunbean (\"lastuser\", \"datasetKey\", \"iid\")");
        execute("CREATE INDEX IF NOT EXISTS idx_ragevalrun_status_time "
                + "ON ragevalrunbean (\"status\", \"startedAt\")");
        execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_ragevalrun_active "
                + "ON ragevalrunbean (\"lastuser\", \"datasetKey\") "
                + "WHERE \"status\" IN ('queued', 'running')");

        execute("CREATE TABLE IF NOT EXISTS ragevalcaseresultbean ("
                + "\"runKey\" varchar(64), \"caseIndex\" int4, \"question\" text, "
                + "\"expectedDoc\" varchar(200), \"expectedKeywords\" text, \"category\" varchar(32), "
                + "\"retrievedDocs\" text, \"topDoc\" varchar(200), \"firstHitRank\" int4, "
                + "\"recall\" float8, \"precision\" float8, \"reciprocalRank\" float8, "
                + "\"ndcg\" float8, \"keywordHit\" boolean, \"keywordEvaluated\" boolean, "
                + "\"durationMs\" int8, \"errorMessage\" text, "
                + "\"iid\" bigserial NOT NULL PRIMARY KEY, \"lastuser\" varchar(255), "
                + "\"createtime\" timestamp DEFAULT now(), \"updatetime\" timestamp, "
                + "\"enable\" boolean DEFAULT true, \"app\" varchar(255))");
        execute("CREATE INDEX IF NOT EXISTS idx_ragevalresult_run "
                + "ON ragevalcaseresultbean (\"runKey\", \"caseIndex\")");
        execute("CREATE INDEX IF NOT EXISTS idx_ragevalresult_user "
                + "ON ragevalcaseresultbean (\"lastuser\")");
    }

    /**
     * 操作日志表（框架写入的 {@code usercontrollerlog}）的时间索引。
     *
     * <p>后台「操作日志」页按 {@code createtime} 区间过滤、按 {@code iid} 倒序分页。
     * 只按 {@code iid} 倒着扫意味着「查三个月前某一天」要从最新一路回退几千行；
     * 建了这个索引，区间过滤走位图扫描再排序，翻页成本与表的总行数不再线性相关。</p>
     *
     * <p>这张表由框架建、由框架写，本项目只读；表不存在时（首次启动前还没有任何
     * 接口调用）这条 DDL 会失败并被忽略，与上面几处一致。</p>
     */
    private void ensureControllerLogIndex() {
        execute("CREATE INDEX IF NOT EXISTS idx_usercontrollerlog_createtime "
                + "ON usercontrollerlog (\"createtime\")");
    }

    /**
     * OpenAI 兼容端点的 API 令牌表（{@code /v1/**} 用的 sk- 开头密钥）。
     *
     * <p>表里只有 {@code "tokenHash"}（SHA-256 十六进制），没有明文：这张表会被备份、
     * 会被调试查询，存明文等于「谁能读库谁就能白拿模型额度」。</p>
     *
     * <p>{@code tokenHash} 建唯一索引有两个作用：鉴权路径按它等值查询；
     * 以及兜住极小概率的哈希碰撞，避免一个明文对应两条记录。</p>
     */
    private void ensureApiTokenTable() {
        execute("CREATE TABLE IF NOT EXISTS aiapitokenbean (\"name\" varchar(100), \"tokenHash\" varchar(64), "
                + "\"tokenPrefix\" varchar(32), \"userId\" varchar(255), \"allowModels\" text, "
                + "\"expireAt\" int8, \"lastUsedAt\" int8, \"iid\" bigserial NOT NULL PRIMARY KEY, "
                + "\"lastuser\" varchar(255), \"createtime\" timestamp DEFAULT now(), \"updatetime\" timestamp, "
                + "\"enable\" boolean DEFAULT true, \"app\" varchar(255))");
        execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_aiapitoken_hash ON aiapitokenbean (\"tokenHash\")");
        execute("CREATE INDEX IF NOT EXISTS idx_aiapitoken_user ON aiapitokenbean (\"userId\")");
    }

    /**
     * 长文本列改为 text。
     *
     * <p>框架按 Java String 自动建表时默认映射 varchar(255)：
     * 一段超过 255 字的会话快照 / RAG 分块正文 / 向量 JSON 都会直接插入失败。
     * 实体上虽然已用 {@code @SqlColumn(method = "text")} 声明，但那只影响新建表；
     * 已经按 varchar(255) 建好的表需要在这里显式放宽，否则升级后依旧报
     * 「value too long for type character varying(255)」。</p>
     */
    private void ensureLongTextColumns() {
        execute("ALTER TABLE kbchunkbean ALTER COLUMN \"content\" TYPE text");
        execute("ALTER TABLE kbchunkbean ALTER COLUMN \"embedding\" TYPE text");
        execute("ALTER TABLE chatconversationbean ALTER COLUMN \"payload\" TYPE text");
        execute("ALTER TABLE chatsharebean ALTER COLUMN \"payload\" TYPE text");
    }

    /**
     * capabilitiesbean 表：新增能力图标字段 icon（text）。
     * 能力广场新增能力时可上传图片，为空时前端显示默认图。
     */
    private void ensureCapabilitiesIconColumn() {
        execute("ALTER TABLE capabilitiesbean ADD COLUMN IF NOT EXISTS icon text");
    }

    /**
     * AI 网关表索引。
     * 表本身由 @HnMapper(createTable=true) 在启动时创建；
     * 这里补索引，保证后台看板按时间范围 / 用户查询时不会全表扫描。
     */
    private void ensureAiGatewayIndexes() {
        execute("CREATE INDEX IF NOT EXISTS idx_aicalllog_createtime ON aicalllogbean (\"createtime\")");
        execute("CREATE INDEX IF NOT EXISTS idx_aicalllog_user_time ON aicalllogbean (\"userId\", \"createtime\")");
        execute("CREATE INDEX IF NOT EXISTS idx_aicalllog_module_time ON aicalllogbean (\"module\", \"createtime\")");
        execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_aiuserquota_user ON aiuserquotabean (\"userId\")");
    }

    /**
     * 本地 RAG 的全文检索索引。
     *
     * <p>混合检索的关键词路用 PostgreSQL 全文检索做候选召回
     * （{@code to_tsvector('simple', content) @@ plainto_tsquery(...)}）。
     * 没有这个表达式索引，每次检索都会对 content 列做全表顺序扫描——
     * 分块数上来以后这一路会比向量路还慢，混合检索就失去意义。</p>
     *
     * <p>用 {@code 'simple'} 配置而不是语言特定的 {@code 'english'} / {@code 'chinese'}：
     * simple 不做词干化、不依赖语言词典，对中英混合的企业文档更稳，
     * 也不会因为缺中文分词器而退化。</p>
     */
    private void ensureKbChunkFullTextIndex() {
        execute("CREATE INDEX IF NOT EXISTS idx_kbchunk_content_fts ON kbchunkbean "
                + "USING gin (to_tsvector('simple', \"content\"))");
    }

    /**
     * RAG 分块的两条高频查询路径：
     * <ul>
     *   <li>检索按 {@code (lastuser[, kbName])} 过滤——缺索引会全表扫描，
     *       且 (lastuser, kbName) 的最左前缀已覆盖只按 lastuser 的查询；</li>
     *   <li>删除文档按 {@code docIid} 清分块——文档多了以后同样是全表扫描。</li>
     * </ul>
     */
    private void ensureKbChunkQueryIndexes() {
        execute("CREATE INDEX IF NOT EXISTS idx_kbchunk_user_kb ON kbchunkbean (\"lastuser\", \"kbName\")");
        execute("CREATE INDEX IF NOT EXISTS idx_kbchunk_doc ON kbchunkbean (\"docIid\")");
    }

    /**
     * RAG 分块记录生成向量所用的模型与维度。
     *
     * <p>只存模型名时，切换到「维度相同」的另一个向量模型无法从维度判断旧分块失效；
     * 记录模型名后，检索可以精确跳过旧模型的向量并提示重新上传。
     * 存量分块为 NULL，语义是「未知模型」，仍按维度兜底判断。</p>
     */
    private void ensureKbChunkEmbeddingModelColumns() {
        execute("ALTER TABLE kbchunkbean ADD COLUMN IF NOT EXISTS \"embeddingModel\" varchar(128)");
        execute("ALTER TABLE kbchunkbean ADD COLUMN IF NOT EXISTS \"embeddingDim\" int4");
    }

    /**
     * 文档处理流水线：状态机字段、去重索引与原始正文表。
     *
     * <p>全部是新增列/新增表，存量文档的 {@code contentHash} 等列为 NULL，
     * 语义就是「旧同步入口写入、不参与去重」，不会被误判为重复。</p>
     *
     * <p>去重唯一索引是并发闸门：两个请求同时上传同一篇正文时，
     * 只靠先查后插会双双通过；索引兜底后，后到者插入失败并返回明确提示。
     * 条件里排除 failed，保证失败文档不阻塞用户重新上传。</p>
     */
    private void ensureKbDocPipeline() {
        execute("ALTER TABLE kbdocbean ADD COLUMN IF NOT EXISTS \"docKey\" varchar(64)");
        execute("ALTER TABLE kbdocbean ADD COLUMN IF NOT EXISTS \"contentHash\" varchar(64)");
        execute("ALTER TABLE kbdocbean ADD COLUMN IF NOT EXISTS \"fileType\" varchar(16)");
        execute("ALTER TABLE kbdocbean ADD COLUMN IF NOT EXISTS \"sizeBytes\" int8");
        execute("ALTER TABLE kbdocbean ADD COLUMN IF NOT EXISTS \"progress\" int4");
        execute("ALTER TABLE kbdocbean ADD COLUMN IF NOT EXISTS \"errorMsg\" text");
        execute("ALTER TABLE kbdocbean ADD COLUMN IF NOT EXISTS \"attemptCount\" int4");
        execute("ALTER TABLE kbdocbean ADD COLUMN IF NOT EXISTS \"startedAt\" int8");
        execute("ALTER TABLE kbdocbean ADD COLUMN IF NOT EXISTS \"finishedAt\" int8");
        execute("ALTER TABLE kbdocbean ADD COLUMN IF NOT EXISTS \"embeddingModel\" varchar(128)");
        execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_kbdoc_key ON kbdocbean (\"docKey\")");
        execute("CREATE INDEX IF NOT EXISTS idx_kbdoc_user_kb_status "
                + "ON kbdocbean (\"lastuser\", \"kbName\", \"status\")");
        execute("CREATE INDEX IF NOT EXISTS idx_kbdoc_user_hash "
                + "ON kbdocbean (\"lastuser\", \"kbName\", \"contentHash\")");
        execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_kbdoc_dedupe "
                + "ON kbdocbean (\"lastuser\", \"kbName\", \"contentHash\") "
                + "WHERE \"contentHash\" IS NOT NULL "
                + "AND \"status\" IN ('queued', 'chunking', 'embedding', 'ready')");

        execute("CREATE TABLE IF NOT EXISTS kbdoccontentbean ("
                + "\"docIid\" int8, \"kbName\" varchar(255), \"content\" text, "
                + "\"contentHash\" varchar(64), \"iid\" bigserial NOT NULL PRIMARY KEY, "
                + "\"lastuser\" varchar(255), \"createtime\" timestamp DEFAULT now(), "
                + "\"updatetime\" timestamp, \"enable\" boolean DEFAULT true, \"app\" varchar(255))");
        execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_kbdoccontent_doc "
                + "ON kbdoccontentbean (\"docIid\")");
        execute("CREATE INDEX IF NOT EXISTS idx_kbdoccontent_user_kb "
                + "ON kbdoccontentbean (\"lastuser\", \"kbName\")");
    }

    /**
     * AI 治理看板的成本核算与全链路追踪列。
     *
     * <p>{@code aicalllogbean.costCents}——调用折算成本（毫分，1 分 = 1000 毫分）。
     * {@code aicalllogbean.requestId}——全链路请求 ID。
     * {@code modelsconfigbean} 的两个价格列——token 单价（分/百万）。</p>
     *
     * <p>全部用 ADD COLUMN IF NOT EXISTS，可重复执行、不影响存量数据：
     * 新增列默认 NULL，语义上就是「未配置」，与代码里的判空一致。</p>
     */
    private void ensureCostAndTracingColumns() {
        execute("ALTER TABLE aicalllogbean ADD COLUMN IF NOT EXISTS \"costCents\" int8");
        execute("ALTER TABLE aicalllogbean ADD COLUMN IF NOT EXISTS \"requestId\" varchar(64)");
        execute("ALTER TABLE aicalllogbean ADD COLUMN IF NOT EXISTS \"firstTokenMs\" int8");

        // 云端会话：直连模型会话的多端同步（JSON 快照按 (userId, convId) upsert）
        execute("CREATE TABLE IF NOT EXISTS chatconversationbean (\"convId\" varchar(64), \"userId\" varchar(255), \"title\" varchar(255), \"modelName\" varchar(255), \"pinned\" boolean, \"payload\" text, \"iid\" bigserial NOT NULL PRIMARY KEY, \"lastuser\" varchar(255), \"createtime\" timestamp DEFAULT now(), \"clientUpdatedAt\" int8, \"updatetime\" timestamp, \"enable\" boolean DEFAULT true, \"app\" varchar(255))");
        execute("CREATE INDEX IF NOT EXISTS idx_chatconv_uid ON chatconversationbean (\"userId\", \"convId\")");
        // 唯一约束兜住并发插入：两端同时首次保存时只允许一行落库，
        // 后到者插入失败后转为按版本号条件更新（见 ChatConversationHolder）。
        // 若历史数据已有重复行，索引创建会失败并留日志——需人工去重，
        // 这里不自动删数据，避免启动时静默丢弃用户会话。
        execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_chatconv_uid_conv ON chatconversationbean (\"userId\", \"convId\")");

        // 对话分享：快照 + 随机 token 访问
        execute("CREATE TABLE IF NOT EXISTS chatsharebean (\"shareToken\" varchar(64), \"title\" varchar(255), \"payload\" text, \"userId\" varchar(255), \"authorName\" varchar(255), \"iid\" bigserial NOT NULL PRIMARY KEY, \"lastuser\" varchar(255), \"createtime\" timestamp DEFAULT now(), \"updatetime\" timestamp, \"enable\" boolean DEFAULT true, \"app\" varchar(255))");
        execute("CREATE INDEX IF NOT EXISTS idx_chatshare_token ON chatsharebean (\"shareToken\")");

        // AI 调用周报：一周一行的治理快照
        execute("CREATE TABLE IF NOT EXISTS aiweeklyreportbean (\"weekStart\" varchar(16), \"weekEnd\" varchar(16), \"totalCalls\" int8, \"successCalls\" int8, \"failedCalls\" int8, \"totalTokens\" int8, \"costCents\" int8, \"avgLatencyMs\" int8, \"avgFirstTokenMs\" int8, \"topUser\" varchar(255), \"topModel\" varchar(255), \"iid\" bigserial NOT NULL PRIMARY KEY, \"lastuser\" varchar(255), \"createtime\" timestamp DEFAULT now(), \"updatetime\" timestamp, \"enable\" boolean DEFAULT true, \"app\" varchar(255))");

        execute("CREATE INDEX IF NOT EXISTS idx_aicalllog_requestid ON aicalllogbean (\"requestId\")");
        // 按模型维度排查（某模型失败率/耗时异常）时避免全表扫描
        execute("CREATE INDEX IF NOT EXISTS idx_aicalllog_model_time ON aicalllogbean (\"modelName\", \"createtime\")");
        execute("ALTER TABLE modelsconfigbean ADD COLUMN IF NOT EXISTS \"priceInputPerMillion\" int8");
        execute("ALTER TABLE modelsconfigbean ADD COLUMN IF NOT EXISTS \"priceOutputPerMillion\" int8");
    }

    /**
     * 周报「一周一行」的唯一约束。
     *
     * <p>周报生成改为「有则更新、无则插入」后，手动触发与周一凌晨的定时任务
     * 仍可能同时发现「本周还没有快照」而双双插入。唯一索引是最后一道闸门：
     * 只放行一条，后到者转去更新赢家行（见 {@code AiWeeklyReportHolder}）。</p>
     *
     * <p>历史数据若已存在同周重复行，先保留 iid 最大（最新）的一行再建索引；
     * 这正是原本「同周重复生成只保留最新一份」的语义。weekStart/weekEnd
     * 为 NULL 的行不参与去重，也不会互相冲突（PostgreSQL 唯一索引允许多个 NULL）。</p>
     */
    private void ensureWeeklyReportUniqueWeek() {
        execute("DELETE FROM aiweeklyreportbean a USING aiweeklyreportbean b "
                + "WHERE a.\"weekStart\" = b.\"weekStart\" "
                + "AND a.\"weekEnd\" = b.\"weekEnd\" "
                + "AND a.\"iid\" < b.\"iid\"");
        execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_aiweeklyreport_week "
                + "ON aiweeklyreportbean (\"weekStart\", \"weekEnd\")");
    }

    /**
     * Agent 工作台的四张表（阶段 1 · 子阶段 1）。
     *
     * <p>拆表理由：配置与工具是「写少读多」的定义数据；运行汇总要能按用户/Agent
     * 分页查历史；步骤明细一次运行可能产生几十行，点开详情时才读。合成一张大表
     * 会让列表页被迫拖着步骤正文。</p>
     *
     * <p>{@code idx_agentrun_active} 是并发闸门：同一用户的同一 Agent 同时只能有
     * 一轮活动运行，避免重复消耗 Token。与 RAG 评估工作台的
     * {@code idx_ragevalrun_active} 是同一套做法。</p>
     *
     * <p>本方法只建表与索引，不改任何既有表；执行循环（子阶段 2）需要按 runKey
     * 归集成本时，再单独给 {@code aicalllogbean} 加两列。</p>
     */
    private void ensureAgentTables() {
        execute("CREATE TABLE IF NOT EXISTS agentbean ("
                + "\"agentKey\" varchar(64), \"name\" varchar(100), \"description\" text, "
                + "\"systemPrompt\" text, \"modelIid\" int8, \"maxIterations\" int4, "
                + "\"tokenBudget\" int8, \"toolTimeoutMs\" int8, \"roleiids\" text, "
                + "\"unitsnos\" text, \"permissionType\" varchar(32), "
                + "\"iid\" bigserial NOT NULL PRIMARY KEY, \"lastuser\" varchar(255), "
                + "\"createtime\" timestamp DEFAULT now(), \"updatetime\" timestamp, "
                + "\"enable\" boolean DEFAULT true, \"app\" varchar(255))");
        execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_key ON agentbean (\"agentKey\")");
        execute("CREATE INDEX IF NOT EXISTS idx_agent_owner ON agentbean (\"lastuser\", \"iid\")");

        execute("CREATE TABLE IF NOT EXISTS agenttoolbean ("
                + "\"agentIid\" int8, \"name\" varchar(100), \"description\" text, "
                + "\"jsonSchema\" text, \"toolType\" varchar(32), \"endpoint\" text, "
                + "\"timeoutMs\" int4, \"maxRetries\" int4, \"roleiids\" text, "
                + "\"unitsnos\" text, "
                + "\"iid\" bigserial NOT NULL PRIMARY KEY, \"lastuser\" varchar(255), "
                + "\"createtime\" timestamp DEFAULT now(), \"updatetime\" timestamp, "
                + "\"enable\" boolean DEFAULT true, \"app\" varchar(255))");
        execute("CREATE INDEX IF NOT EXISTS idx_agenttool_agent "
                + "ON agenttoolbean (\"agentIid\", \"lastuser\")");

        execute("CREATE TABLE IF NOT EXISTS agentrunbean ("
                + "\"runKey\" varchar(64), \"agentIid\" int8, \"status\" varchar(16), "
                + "\"question\" text, \"iterations\" int4, \"promptTokens\" int8, "
                + "\"completionTokens\" int8, \"totalTokens\" int8, \"costCents\" int8, "
                + "\"errorMessage\" text, \"requestId\" varchar(64), \"startedAt\" int8, "
                + "\"finishedAt\" int8, \"durationMs\" int8, \"answer\" text, "
                + "\"iid\" bigserial NOT NULL PRIMARY KEY, \"lastuser\" varchar(255), "
                + "\"createtime\" timestamp DEFAULT now(), \"updatetime\" timestamp, "
                + "\"enable\" boolean DEFAULT true, \"app\" varchar(255))");
        execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_agentrun_key ON agentrunbean (\"runKey\")");
        execute("CREATE INDEX IF NOT EXISTS idx_agentrun_user_agent "
                + "ON agentrunbean (\"lastuser\", \"agentIid\", \"createtime\")");
        execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_agentrun_active ON agentrunbean "
                + "(\"agentIid\", \"lastuser\") WHERE \"status\" IN ('queued', 'running')");

        execute("CREATE TABLE IF NOT EXISTS agentstepbean ("
                + "\"runKey\" varchar(64), \"runIid\" int8, \"stepNo\" int4, "
                + "\"iteration\" int4, \"stepType\" varchar(16), \"toolName\" varchar(100), "
                + "\"toolArgs\" text, \"toolResult\" text, \"status\" varchar(16), "
                + "\"promptTokens\" int8, \"completionTokens\" int8, \"costCents\" int8, "
                + "\"latencyMs\" int8, \"errorMessage\" text, \"requestId\" varchar(64), "
                + "\"callLogIid\" int8, "
                + "\"iid\" bigserial NOT NULL PRIMARY KEY, \"lastuser\" varchar(255), "
                + "\"createtime\" timestamp DEFAULT now(), \"updatetime\" timestamp, "
                + "\"enable\" boolean DEFAULT true, \"app\" varchar(255))");
        execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_agentstep_run_step "
                + "ON agentstepbean (\"runKey\", \"stepNo\")");

        // 已有表加列必须显式 ALTER：CREATE TABLE IF NOT EXISTS 对已存在的表不补列
        execute("ALTER TABLE agentrunbean ADD COLUMN IF NOT EXISTS \"answer\" text");
        execute("ALTER TABLE aicalllogbean ADD COLUMN IF NOT EXISTS \"runKey\" varchar(64)");
        execute("ALTER TABLE aicalllogbean ADD COLUMN IF NOT EXISTS \"stepNo\" int4");
        execute("CREATE INDEX IF NOT EXISTS idx_aicalllog_runkey "
                + "ON aicalllogbean (\"runKey\")");
    }

    /**
     * 执行一条 DDL。
     * <p>
     * <b>约定</b>：凡是驼峰列名，SQL 里必须写成带双引号的形式（如 {@code "agentId"}）。
     * PostgreSQL 会把未加引号的标识符折叠成小写，写 {@code agentId} 实际建的是
     * {@code agentid}，与框架按字段名原样生成的 {@code "agentId"} 不是同一列，
     * 会在同一张表里留下大小写重复列。
     */
    private void execute(String sql) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
            log.info("[TableSchemaInitializer] 执行成功: {}", sql);
        } catch (Exception e) {
            // 列已存在 / 表不存在等情况不应阻断启动，仅记录日志
            log.warn("[TableSchemaInitializer] 执行失败(可忽略): {}，原因: {}", sql, e.getMessage());
        }
    }
}
