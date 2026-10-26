-- 清空评估语料（mock 1536 维向量作废，换 bge-m3 1024 维需重灌）
DELETE FROM kbchunkbean WHERE "kbName" = '评估语料';
DELETE FROM kbdocbean  WHERE "kbName" = '评估语料';
