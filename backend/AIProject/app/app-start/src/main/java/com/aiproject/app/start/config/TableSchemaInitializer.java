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
        ensureLongTextColumns();
        ensureCostAndTracingColumns();
        ensureApiTokenTable();
        ensureControllerLogIndex();
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
     * AI 治理看板的成本核算与全链路追踪列。
     *
     * <p>{@code aicalllogbean.costCents}——调用折算成本（分）。
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
        execute("ALTER TABLE modelsconfigbean ADD COLUMN IF NOT EXISTS \"priceInputPerMillion\" int8");
        execute("ALTER TABLE modelsconfigbean ADD COLUMN IF NOT EXISTS \"priceOutputPerMillion\" int8");
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
