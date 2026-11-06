-- 站内通知建表与索引（PostgreSQL）
--
-- 应用启动时 TableSchemaInitializer 会自动执行同样结构的幂等 DDL；
-- 这个文件用于「不重启应用、由 DBA 手工迁移」或其它环境复制结构。
-- 两边必须保持一致，修改时同步更新：
--   backend/AIProject/app/app-start/src/main/java/com/aiproject/app/start/config/TableSchemaInitializer.java
--
-- 所有语句可重复执行（IF NOT EXISTS）。

BEGIN;

CREATE TABLE IF NOT EXISTS notificationbean (
    "eventKey" varchar(128),
    "category" varchar(32),
    "level" varchar(16),
    "title" varchar(200),
    "content" text,
    "link" varchar(255),
    "readStatus" varchar(16),
    "readAt" int8,
    "iid" bigserial NOT NULL PRIMARY KEY,
    "lastuser" varchar(255),
    "createtime" timestamp DEFAULT now(),
    "updatetime" timestamp,
    "enable" boolean DEFAULT true,
    "app" varchar(255)
);

-- 幂等键：同一事件重复收尾只保留一条通知
CREATE UNIQUE INDEX IF NOT EXISTS idx_notification_event
    ON notificationbean ("eventKey");

-- 面板按用户时间倒序翻页
CREATE INDEX IF NOT EXISTS idx_notification_user_time
    ON notificationbean ("lastuser", "iid");

-- 铃铛角标按用户数未读
CREATE INDEX IF NOT EXISTS idx_notification_user_unread
    ON notificationbean ("lastuser", "readStatus");

COMMIT;
