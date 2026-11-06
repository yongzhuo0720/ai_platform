-- RAG 评估工作台建表与索引（PostgreSQL）
--
-- 应用启动时 TableSchemaInitializer 会自动执行同样结构的幂等 DDL；
-- 这个文件用于「不重启应用、由 DBA 手工迁移」或其它环境复制结构。
-- 两边必须保持一致，修改时同步更新：
--   backend/AIProject/app/app-start/src/main/java/com/aiproject/app/start/config/TableSchemaInitializer.java
--
-- 所有语句可重复执行（IF NOT EXISTS）。

BEGIN;

CREATE TABLE IF NOT EXISTS ragevaldatasetbean (
    "datasetKey" varchar(64),
    "name" varchar(100),
    "kbName" varchar(100),
    "description" text,
    "caseCount" int4,
    "caseSetKey" varchar(64),
    "iid" bigserial NOT NULL PRIMARY KEY,
    "lastuser" varchar(255),
    "createtime" timestamp DEFAULT now(),
    "updatetime" timestamp,
    "enable" boolean DEFAULT true,
    "app" varchar(255)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ragevaldataset_key
    ON ragevaldatasetbean ("datasetKey");
CREATE UNIQUE INDEX IF NOT EXISTS idx_ragevaldataset_owner_name
    ON ragevaldatasetbean ("lastuser", "name");

CREATE TABLE IF NOT EXISTS ragevalcasebean (
    "datasetKey" varchar(64),
    "caseSetKey" varchar(64),
    "question" text,
    "expectedDoc" varchar(200),
    "expectedKeywords" text,
    "category" varchar(32),
    "iid" bigserial NOT NULL PRIMARY KEY,
    "lastuser" varchar(255),
    "createtime" timestamp DEFAULT now(),
    "updatetime" timestamp,
    "enable" boolean DEFAULT true,
    "app" varchar(255)
);
CREATE INDEX IF NOT EXISTS idx_ragevalcase_dataset_set
    ON ragevalcasebean ("datasetKey", "caseSetKey");
CREATE INDEX IF NOT EXISTS idx_ragevalcase_user
    ON ragevalcasebean ("lastuser");

CREATE TABLE IF NOT EXISTS ragevalrunbean (
    "runKey" varchar(64),
    "datasetKey" varchar(64),
    "caseSetKey" varchar(64),
    "datasetName" varchar(100),
    "kbName" varchar(100),
    "topK" int4,
    "status" varchar(16),
    "total" int4,
    "processed" int4,
    "recall" float8,
    "precision" float8,
    "mrr" float8,
    "ndcg" float8,
    "keywordHitRate" float8,
    "missCount" int4,
    "keywordEvaluatedCount" int4,
    "embeddingModel" varchar(128),
    "metricSpec" varchar(32),
    "retrievalMode" varchar(32),
    "rerankApplied" boolean,
    "rerankMode" varchar(16),
    "errorMessage" text,
    "startedAt" int8,
    "finishedAt" int8,
    "durationMs" int8,
    "iid" bigserial NOT NULL PRIMARY KEY,
    "lastuser" varchar(255),
    "createtime" timestamp DEFAULT now(),
    "updatetime" timestamp,
    "enable" boolean DEFAULT true,
    "app" varchar(255)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ragevalrun_key
    ON ragevalrunbean ("runKey");
CREATE INDEX IF NOT EXISTS idx_ragevalrun_user_dataset
    ON ragevalrunbean ("lastuser", "datasetKey", "iid");
CREATE INDEX IF NOT EXISTS idx_ragevalrun_status_time
    ON ragevalrunbean ("status", "startedAt");
-- 同一用户对同一数据集最多一个活动运行。僵死运行由应用定时回收。
CREATE UNIQUE INDEX IF NOT EXISTS idx_ragevalrun_active
    ON ragevalrunbean ("lastuser", "datasetKey")
    WHERE "status" IN ('queued', 'running');

CREATE TABLE IF NOT EXISTS ragevalcaseresultbean (
    "runKey" varchar(64),
    "caseIndex" int4,
    "question" text,
    "expectedDoc" varchar(200),
    "expectedKeywords" text,
    "category" varchar(32),
    "retrievedDocs" text,
    "retrievedHits" text,
    "topDoc" varchar(200),
    "firstHitRank" int4,
    "recall" float8,
    "precision" float8,
    "reciprocalRank" float8,
    "ndcg" float8,
    "keywordHit" boolean,
    "keywordEvaluated" boolean,
    "durationMs" int8,
    "errorMessage" text,
    "iid" bigserial NOT NULL PRIMARY KEY,
    "lastuser" varchar(255),
    "createtime" timestamp DEFAULT now(),
    "updatetime" timestamp,
    "enable" boolean DEFAULT true,
    "app" varchar(255)
);
CREATE INDEX IF NOT EXISTS idx_ragevalresult_run
    ON ragevalcaseresultbean ("runKey", "caseIndex");
CREATE INDEX IF NOT EXISTS idx_ragevalresult_user
    ON ragevalcaseresultbean ("lastuser");

-- 存量环境补列（CREATE TABLE IF NOT EXISTS 不会修改已存在的表）：
-- Top-K 明细、用例集版本与运行配置快照。旧运行保持 NULL，界面按「未记录」处理。
ALTER TABLE ragevalrunbean
    ADD COLUMN IF NOT EXISTS "caseSetKey" varchar(64);
ALTER TABLE ragevalrunbean
    ADD COLUMN IF NOT EXISTS "metricSpec" varchar(32);
ALTER TABLE ragevalrunbean
    ADD COLUMN IF NOT EXISTS "retrievalMode" varchar(32);
ALTER TABLE ragevalrunbean
    ADD COLUMN IF NOT EXISTS "rerankApplied" boolean;
ALTER TABLE ragevalrunbean
    ADD COLUMN IF NOT EXISTS "rerankMode" varchar(16);
ALTER TABLE ragevalcaseresultbean
    ADD COLUMN IF NOT EXISTS "retrievedHits" text;

COMMIT;

-- 维护查询（默认不执行）：清理「编辑用例集时旧版本删除失败」留下的不可见用例。
-- 只会删除 dataset.caseSetKey 已经不再指向的旧版本；正在使用的用例不会命中。
--
-- SELECT count(*) FROM ragevalcasebean c
--  WHERE NOT EXISTS (
--      SELECT 1 FROM ragevaldatasetbean d
--       WHERE d."datasetKey" = c."datasetKey"
--         AND d."caseSetKey" = c."caseSetKey");
--
-- DELETE FROM ragevalcasebean c
--  WHERE NOT EXISTS (
--      SELECT 1 FROM ragevaldatasetbean d
--       WHERE d."datasetKey" = c."datasetKey"
--         AND d."caseSetKey" = c."caseSetKey");
