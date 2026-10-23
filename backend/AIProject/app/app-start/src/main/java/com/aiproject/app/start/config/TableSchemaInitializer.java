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
