-- 文档处理流水线（PostgreSQL）
--
-- 应用启动时 TableSchemaInitializer.ensureKbDocPipeline() 会执行同样的幂等 DDL；
-- 这个文件用于「不重启应用、由 DBA 手工迁移」或其它环境复制结构。
-- 两边必须保持一致，修改时同步更新：
--   backend/AIProject/app/app-start/src/main/java/com/aiproject/app/start/config/TableSchemaInitializer.java
--
-- 所有语句可重复执行（IF NOT EXISTS），不删除任何旧列。
-- 存量文档 contentHash/docKey 为 NULL，表示「旧同步入口写入、不参与去重」。
-- 回滚方式：停止使用新列/新表即可；如需物理回滚，先备份再手动 DROP。
-- 不在本脚本里自动 DROP，避免误删新入口已经写入的文档正文。

BEGIN;

ALTER TABLE kbdocbean ADD COLUMN IF NOT EXISTS "docKey" varchar(64);
ALTER TABLE kbdocbean ADD COLUMN IF NOT EXISTS "contentHash" varchar(64);
ALTER TABLE kbdocbean ADD COLUMN IF NOT EXISTS "fileType" varchar(16);
ALTER TABLE kbdocbean ADD COLUMN IF NOT EXISTS "sizeBytes" int8;
ALTER TABLE kbdocbean ADD COLUMN IF NOT EXISTS "progress" int4;
ALTER TABLE kbdocbean ADD COLUMN IF NOT EXISTS "errorMsg" text;
ALTER TABLE kbdocbean ADD COLUMN IF NOT EXISTS "attemptCount" int4;
ALTER TABLE kbdocbean ADD COLUMN IF NOT EXISTS "startedAt" int8;
ALTER TABLE kbdocbean ADD COLUMN IF NOT EXISTS "finishedAt" int8;
ALTER TABLE kbdocbean ADD COLUMN IF NOT EXISTS "embeddingModel" varchar(128);

CREATE UNIQUE INDEX IF NOT EXISTS idx_kbdoc_key
    ON kbdocbean ("docKey");
CREATE INDEX IF NOT EXISTS idx_kbdoc_user_kb_status
    ON kbdocbean ("lastuser", "kbName", "status");
CREATE INDEX IF NOT EXISTS idx_kbdoc_user_hash
    ON kbdocbean ("lastuser", "kbName", "contentHash");
-- 同一用户在同一知识库下，同一正文只允许一条活动/已就绪记录。
-- failed 不参与，保证失败文档可以重新上传。
CREATE UNIQUE INDEX IF NOT EXISTS idx_kbdoc_dedupe
    ON kbdocbean ("lastuser", "kbName", "contentHash")
    WHERE "contentHash" IS NOT NULL
      AND "status" IN ('queued', 'chunking', 'embedding', 'ready');

CREATE TABLE IF NOT EXISTS kbdoccontentbean (
    "docIid" int8,
    "kbName" varchar(255),
    "content" text,
    "contentHash" varchar(64),
    "iid" bigserial NOT NULL PRIMARY KEY,
    "lastuser" varchar(255),
    "createtime" timestamp DEFAULT now(),
    "updatetime" timestamp,
    "enable" boolean DEFAULT true,
    "app" varchar(255)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_kbdoccontent_doc
    ON kbdoccontentbean ("docIid");
CREATE INDEX IF NOT EXISTS idx_kbdoccontent_user_kb
    ON kbdoccontentbean ("lastuser", "kbName");

COMMIT;

-- 维护查询（默认不执行）：清理「文档已删除但正文行残留」的孤儿数据。
-- 正常删除路径（deleteDoc / 删除知识库）会同步清理；这里只用于历史数据排查。
--
-- SELECT count(*) FROM kbdoccontentbean c
--  WHERE NOT EXISTS (
--      SELECT 1 FROM kbdocbean d WHERE d."iid" = c."docIid");
