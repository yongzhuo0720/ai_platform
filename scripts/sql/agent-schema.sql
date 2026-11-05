-- Agent 工作台（阶段 1 · 子阶段 1）表结构，PostgreSQL
--
-- 应用启动时 TableSchemaInitializer.ensureAgentTables() 会执行同样的幂等 DDL；
-- 这个文件用于「不重启应用、由 DBA 手工迁移」或其它环境复制结构。
-- 两边必须保持一致，修改时同步更新：
--   backend/AIProject/app/app-start/src/main/java/com/aiproject/app/start/config/TableSchemaInitializer.java
--
-- 全部语句可重复执行（IF NOT EXISTS），不删除任何旧列、不改任何既有表。
-- 驼峰列名一律双引号：PostgreSQL 会把未加引号的标识符折叠成小写，
-- 与框架按字段名生成的 "agentKey" 变成两列（项目踩过这个坑）。
-- 回滚：停止使用这四张表即可；如需物理回滚，先备份再手工 DROP，脚本不自动删。

BEGIN;

-- Agent 配置
CREATE TABLE IF NOT EXISTS agentbean (
    "agentKey" varchar(64),
    "name" varchar(100),
    "description" text,
    "systemPrompt" text,
    "modelIid" int8,
    "maxIterations" int4,
    "tokenBudget" int8,
    "toolTimeoutMs" int8,
    "roleiids" text,
    "unitsnos" text,
    "permissionType" varchar(32),
    "iid" bigserial NOT NULL PRIMARY KEY,
    "lastuser" varchar(255),
    "createtime" timestamp DEFAULT now(),
    "updatetime" timestamp,
    "enable" boolean DEFAULT true,
    "app" varchar(255)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_key ON agentbean ("agentKey");
CREATE INDEX IF NOT EXISTS idx_agent_owner ON agentbean ("lastuser", "iid");

-- 工具定义（jsonSchema 必填，执行器据此校验模型生成的参数）
CREATE TABLE IF NOT EXISTS agenttoolbean (
    "agentIid" int8,
    "name" varchar(100),
    "description" text,
    "jsonSchema" text,
    "toolType" varchar(32),
    "endpoint" text,
    "timeoutMs" int4,
    "maxRetries" int4,
    "roleiids" text,
    "unitsnos" text,
    "iid" bigserial NOT NULL PRIMARY KEY,
    "lastuser" varchar(255),
    "createtime" timestamp DEFAULT now(),
    "updatetime" timestamp,
    "enable" boolean DEFAULT true,
    "app" varchar(255)
);
CREATE INDEX IF NOT EXISTS idx_agenttool_agent ON agenttoolbean ("agentIid", "lastuser");

-- 运行汇总
CREATE TABLE IF NOT EXISTS agentrunbean (
    "runKey" varchar(64),
    "agentIid" int8,
    "status" varchar(16),
    "question" text,
    "iterations" int4,
    "promptTokens" int8,
    "completionTokens" int8,
    "totalTokens" int8,
    "costCents" int8,
    "errorMessage" text,
    "requestId" varchar(64),
    "startedAt" int8,
    "finishedAt" int8,
    "durationMs" int8,
    "answer" text,
    "iid" bigserial NOT NULL PRIMARY KEY,
    "lastuser" varchar(255),
    "createtime" timestamp DEFAULT now(),
    "updatetime" timestamp,
    "enable" boolean DEFAULT true,
    "app" varchar(255)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_agentrun_key ON agentrunbean ("runKey");
CREATE INDEX IF NOT EXISTS idx_agentrun_user_agent
    ON agentrunbean ("lastuser", "agentIid", "createtime");
-- 并发闸门：同一用户的同一 Agent 同时只能有一轮活动运行
CREATE UNIQUE INDEX IF NOT EXISTS idx_agentrun_active
    ON agentrunbean ("agentIid", "lastuser")
    WHERE "status" IN ('queued', 'running');

-- 步骤明细
CREATE TABLE IF NOT EXISTS agentstepbean (
    "runKey" varchar(64),
    "runIid" int8,
    "stepNo" int4,
    "iteration" int4,
    "stepType" varchar(16),
    "toolName" varchar(100),
    "toolArgs" text,
    "toolResult" text,
    "status" varchar(16),
    "promptTokens" int8,
    "completionTokens" int8,
    "costCents" int8,
    "latencyMs" int8,
    "errorMessage" text,
    "requestId" varchar(64),
    "callLogIid" int8,
    "iid" bigserial NOT NULL PRIMARY KEY,
    "lastuser" varchar(255),
    "createtime" timestamp DEFAULT now(),
    "updatetime" timestamp,
    "enable" boolean DEFAULT true,
    "app" varchar(255)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_agentstep_run_step
    ON agentstepbean ("runKey", "stepNo");

-- Agent 编排里的每次模型调用都挂到 runKey / stepNo 上，
-- 治理看板据此按运行聚合成本（复用已有计价，不另起一套表）。
-- 注意：这是给「已存在的表」加列，必须写 ALTER，CREATE TABLE IF NOT EXISTS 不补列。
-- agentrunbean 若由上一子阶段建过，同样需要补丁（answer 是子阶段 2 新增列）。
ALTER TABLE agentrunbean ADD COLUMN IF NOT EXISTS "answer" text;
ALTER TABLE aicalllogbean ADD COLUMN IF NOT EXISTS "runKey" varchar(64);
ALTER TABLE aicalllogbean ADD COLUMN IF NOT EXISTS "stepNo" int4;
CREATE INDEX IF NOT EXISTS idx_aicalllog_runkey ON aicalllogbean ("runKey");

COMMIT;
