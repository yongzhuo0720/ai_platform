-- 清理历史遗留的孤儿知识库分块（kbchunkbean 有记录、对应 kbdocbean 已不存在）
--
-- 背景：
--   旧版删除文档时先删 kbdocbean、再删 kbchunkbean；第二步失败（超时/连接中断）
--   就会留下仍可被 RAG 检索到的分块。旧版删除知识库也没有级联本地文档与分块。
--   代码侧已改为「先分块、后文档」删除，并在删除知识库时级联清理；
--   本脚本只处理存量数据，不改变表结构，可重复执行。
--
-- 执行方案：
--   1. 备份：pg_dump -Fc <db> > backup_before_kbchunk_cleanup.dump
--   2. 预检：执行「预检」段，确认孤儿行数与抽样内容符合预期（应为历史残留，
--      且 content 属于已删除文档；如数量异常先不要继续）。
--   3. 清理：在同一事务里执行「清理」段；DELETE 行数应与预检行数一致。
--   4. 复核：再次执行预检，应返回 0 行。
--   5. 清理后建议重启后端或等待 10 分钟缓存过期：检索侧若已缓存分块，需重新加载。
--
-- 注意：
--   - 只按 docIid 判断引用关系；docIid 为 NULL 的分块无法关联任何文档，同样属于孤儿。
--   - 不做「知识库不存在就删文档」：kbName 与 Dify 数据集名称的对应关系由业务层维护，
--     仅凭名称缺失判断会误删正在使用的本地文档。

-- ============ 预检 ============
SELECT c."iid",
       c."docIid",
       c."kbName",
       c."lastuser",
       left(c."content", 60) AS content_sample
FROM kbchunkbean c
WHERE c."docIid" IS NULL
   OR NOT EXISTS (
       SELECT 1 FROM kbdocbean d WHERE d."iid" = c."docIid"
   )
ORDER BY c."iid";

-- ============ 清理 ============
BEGIN;

DELETE FROM kbchunkbean c
WHERE c."docIid" IS NULL
   OR NOT EXISTS (
       SELECT 1 FROM kbdocbean d WHERE d."iid" = c."docIid"
   );

COMMIT;

-- ============ 复核（应返回 0） ============
SELECT count(*) AS orphan_chunks
FROM kbchunkbean c
WHERE c."docIid" IS NULL
   OR NOT EXISTS (
       SELECT 1 FROM kbdocbean d WHERE d."iid" = c."docIid"
   );
