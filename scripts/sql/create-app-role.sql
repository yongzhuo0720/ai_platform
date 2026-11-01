-- 应用专用低权限数据库账号 ai_app（PostgreSQL 15+ / 18 实测）
--
-- 要解决的问题：后端一直用 postgres（超级用户）连库。应用被注入任意 SQL 或
-- 拿到库口令时，超级用户能读 pg_shadow、CREATE ROLE、甚至 COPY 到文件服务磁盘。
-- 数据库账号是最后一层爆炸半径控制，和业务鉴权不是一回事，两者都要有。
--
-- 为什么必须转移「归属」而不只给 SELECT/INSERT/UPDATE/DELETE：
--   1. 启动期 TableSchemaInitializer 要跑 ALTER TABLE ... ADD COLUMN / CREATE INDEX，
--      这两类 DDL 要求执行者是表的 owner，仅有表权限会失败；
--   2. 框架的 @HnMapper(createTable=true) 会在启动时建表，需要 schema 的 CREATE 权限；
--   3. TableSchemaInitializer 把 DDL 失败当作「可忽略」只打 WARN——
--      也就是说权限不够时应用照样启动，但长文本列、索引会静默缺失，
--      表现为「插入超长内容报错」「检索全表扫描」这类难以归因的问题。
--      所以执行完本脚本必须按文件末尾的「验证」段确认权限真的够。
--
-- 用法（PowerShell，口令不写进仓库，临时生成并交给 psql）：
--   $pwd = -join ((48..57)+(97..122) | Get-Random -Count 24 | % {[char]$_})
--   .\hd_redis\pgsql\bin\psql.exe -h 127.0.0.1 -U postgres -d ai `
--     -v app_password="$pwd" -f scripts\sql\create-app-role.sql
--   然后把 application-dev.properties 的 hnsp.datasource.username/password 改成
--   ai_app 与上面的口令（或改用 AI_DB_USERNAME / AI_DB_PASSWORD 环境变量）。
--   注意本脚本只需执行一次；scripts/start.ps1 只负责拉起 PostgreSQL，不会代你做这一步。
--
-- 只需执行一次。重复执行会在 CREATE ROLE 处报错，此时先按「回滚」段把归属交回
-- postgres 再 DROP ROLE ai_app。
--
-- 回滚（想让应用重新用超级用户启动时）：
--   DO $rb$ DECLARE r record; BEGIN
--     FOR r IN SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
--              WHERE n.nspname='public' AND c.relkind IN ('r','S')
--                AND c.relowner='ai_app'::regrole LOOP
--       EXECUTE format('ALTER TABLE public.%I OWNER TO postgres', r.relname);
--     END LOOP; END $rb$;
--   DROP ROLE ai_app;

\set ON_ERROR_STOP on

-- ============ 1. 角色 ============
-- 只给 LOGIN。刻意不给 SUPERUSER / CREATEDB / CREATEROLE / BYPASSRLS / REPLICATION，
-- 这样即使应用侧被注入 SQL，也读不到其他库、建不了新角色。
CREATE ROLE ai_app LOGIN PASSWORD :'app_password';

-- 只允许连本项目使用的库；换库名时同步改这里
REVOKE ALL ON DATABASE ai FROM ai_app;
GRANT CONNECT ON DATABASE ai TO ai_app;

-- ============ 2. schema 权限 ============
-- CREATE 是建表需要的；PG15 起 public schema 默认已不再对 PUBLIC 开放 CREATE，
-- 这里显式授权，不依赖默认值。
REVOKE ALL ON SCHEMA public FROM ai_app;
GRANT USAGE, CREATE ON SCHEMA public TO ai_app;

-- ============ 3. 存量表/序列归属转移 ============
-- owner 才能 ALTER / CREATE INDEX；bigserial 背后的序列单独转移。
DO $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT c.oid::regclass AS name
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public'
          AND c.relkind IN ('r', 'p')
          AND c.relowner = 'postgres'::regrole
    LOOP
        EXECUTE format('ALTER TABLE %s OWNER TO ai_app', r.name);
    END LOOP;

    FOR r IN
        SELECT c.oid::regclass AS name
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public'
          AND c.relkind = 'S'
          AND c.relowner = 'postgres'::regrole
    LOOP
        EXECUTE format('ALTER SEQUENCE %s OWNER TO ai_app', r.name);
    END LOOP;
END
$$;

-- ============ 4. 兜底：以后由超级用户建的表也归应用可用 ============
-- 正常流程下新表由 ai_app 自己创建并持有；这条只是防止人工用 postgres 建表后应用无权访问。
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ai_app;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO ai_app;

-- ============ 验证（执行后必须逐条看到预期结果） ============
-- 预期 false / false / false：不是超级用户，也不能建角色、建库
SELECT rolname, rolsuper, rolcreaterole, rolcreatedb
FROM pg_roles WHERE rolname = 'ai_app';

-- 预期 0：public 下已没有不属于 ai_app 的表或序列
SELECT count(*) AS not_owned_by_ai_app
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public'
  AND c.relkind IN ('r', 'S')
  AND c.relowner <> 'ai_app'::regrole;

-- 预期 2 行：CONNECT + USAGE/CREATE 已生效
SELECT has_database_privilege('ai_app', 'ai', 'CONNECT') AS can_connect,
       has_schema_privilege('ai_app', 'public', 'CREATE') AS can_create_table;

-- 预期 permission denied：低权限账号读不了系统口令视图
-- 这条要手工用 ai_app 连接执行：
--   PGPASSWORD=... psql -h 127.0.0.1 -U ai_app -d ai -c "select rolname,rolpassword from pg_authid"
