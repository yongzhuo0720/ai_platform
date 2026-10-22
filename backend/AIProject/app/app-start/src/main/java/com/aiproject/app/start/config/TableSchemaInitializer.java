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
        ensureAssistantColumns();
        ensureAiGatewayIndexes();
    }

    /**
     * capabilitiesbean 表：新增能力图标字段 icon（text）。
     * 能力广场新增能力时可上传图片，为空时前端显示默认图。
     */
    private void ensureCapabilitiesIconColumn() {
        execute("ALTER TABLE capabilitiesbean ADD COLUMN IF NOT EXISTS icon text");
    }

    /**
     * assistantbean 表：多智能体统一管理所需的配置字段。
     * 注意：框架 SQL 使用「字段名原样 + 双引号」作为列名（如 "userId"），
     * 因此列名必须与 Java 字段名完全一致（驼峰），不能使用下划线。
     */
    private void ensureAssistantColumns() {
        // 先清理历史遗留的小写重复列。
        // 之前的实现用未加引号的 DDL 建列（ALTER TABLE ... ADD COLUMN agentId text），
        // PostgreSQL 会把未加引号的标识符折叠成小写，于是建出的是 agentid 而不是 agentId，
        // 与框架需要的 "agentId" 形成同表内的一对大小写重复列。
        // 自动映射大小写不敏感，一旦两列都有值就会把值映射到错误的列上。
        // 这 4 列在库中始终为空（写入走框架生成的带引号列名），因此可安全删除。
        execute("ALTER TABLE assistantbean DROP COLUMN IF EXISTS agentid");
        execute("ALTER TABLE assistantbean DROP COLUMN IF EXISTS apiurl");
        execute("ALTER TABLE assistantbean DROP COLUMN IF EXISTS authtoken");
        execute("ALTER TABLE assistantbean DROP COLUMN IF EXISTS permissiontype");

        execute("ALTER TABLE assistantbean ADD COLUMN IF NOT EXISTS \"type\" text");
        execute("ALTER TABLE assistantbean ADD COLUMN IF NOT EXISTS \"agentId\" text");
        execute("ALTER TABLE assistantbean ADD COLUMN IF NOT EXISTS \"apiUrl\" text");
        execute("ALTER TABLE assistantbean ADD COLUMN IF NOT EXISTS \"authToken\" text");
        execute("ALTER TABLE assistantbean ADD COLUMN IF NOT EXISTS \"channel\" text");
        execute("ALTER TABLE assistantbean ADD COLUMN IF NOT EXISTS \"permissionType\" text");
        execute("ALTER TABLE assistantbean ADD COLUMN IF NOT EXISTS \"roleiids\" text");
        execute("ALTER TABLE assistantbean ADD COLUMN IF NOT EXISTS \"unitsnos\" text");
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
