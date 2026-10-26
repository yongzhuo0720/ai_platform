package com.aiproject.ai.service.rag;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 黄金评估集与种子语料的一致性校验。
 *
 * <p><b>为什么需要这道守卫</b>：评估集最怕的不是"没有"，而是<b>悄悄失效</b>——
 * 有人改了一篇制度文档、删掉了某个数字，但没同步更新题目；之后跑评估，
 * 那道题永远召不回正确答案，指标莫名其妙掉了，却没人知道是题目过期还是检索变差。
 * 这个测试把这种漂移在提交时就拦住。</p>
 *
 * <p>校验四件事：</p>
 * <ol>
 *   <li>每题的 {@code expected_doc} 在语料目录里真实存在；</li>
 *   <li>每题的 {@code expected_keywords} 每一个词都真的出现在那篇文档里；</li>
 *   <li>题目没有重复（重复题会让指标被同一类问题拉偏）；</li>
 *   <li>CSV 列数完整（防止字段里的逗号把数据挤错列）。</li>
 * </ol>
 *
 * <p>找不到 docs 目录时跳过而不是失败——这个测试服务于仓库内的评估集，
 * 不应因为换了工作目录就让整条构建挂掉。</p>
 */
class GoldenSetConsistencyTest {

    /** 从模块目录（app-business/app-business-service）回到仓库根 */
    private static final String EVAL_DIR = "../../../../docs/eval";

    private File evalDir() {
        return new File(EVAL_DIR);
    }

    private File corpusDir() {
        return new File(evalDir(), "seed-corpus");
    }

    private File goldenSet() {
        return new File(evalDir(), "golden-set.csv");
    }

    /** 读取语料：文件名（去掉 .md）→ 全文 */
    private Map<String, String> loadCorpus() throws Exception {
        Map<String, String> corpus = new HashMap<String, String>();
        File[] files = corpusDir().listFiles();
        if (files == null) {
            return corpus;
        }
        for (File f : files) {
            if (!f.getName().endsWith(".md")) {
                continue;
            }
            String name = f.getName().substring(0, f.getName().length() - 3);
            corpus.put(name, new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        }
        return corpus;
    }

    /** 读取黄金集，跳过表头 */
    private List<String[]> loadGoldenSet() throws Exception {
        List<String[]> rows = new ArrayList<String[]>();
        List<String> lines = Files.readAllLines(goldenSet().toPath(), StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            rows.add(line.split(",", -1));
        }
        return rows;
    }

    /** docs 目录不在时跳过整个测试类 */
    private boolean evalResourcesAvailable() {
        return goldenSet().isFile() && corpusDir().isDirectory();
    }

    /** 每题的标准答案文档都必须在语料里存在 */
    @Test
    void everyExpectedDocShouldExist() throws Exception {
        Assumptions.assumeTrue(evalResourcesAvailable(), "评估资源不存在，跳过");

        Map<String, String> corpus = loadCorpus();
        List<String[]> rows = loadGoldenSet();

        List<String> missing = new ArrayList<String>();
        for (String[] row : rows) {
            String id = row[0];
            String expectedDoc = row[2];
            if (!corpus.containsKey(expectedDoc)) {
                missing.add(id + " -> " + expectedDoc);
            }
        }
        assertTrue(missing.isEmpty(),
                "黄金集引用了不存在的文档（改语料后忘了改题？）：" + missing);
    }

    /**
     * 比较前去掉千分位分隔符。
     *
     * <p>语料里为了可读性写 {@code 200,000}，而黄金集中不能出现逗号
     * （CSV 用逗号分列），所以写成 {@code 200000}。两者语义相同，
     * 比较时忽略分隔符即可。</p>
     */
    private static String normalizeDigits(String text) {
        return text == null ? "" : text.replace(",", "").replace("，", "");
    }

    /**
     * 每题的关键词都必须真的出现在其标准答案文档里。
     *
     * <p>这条最关键：文档里的数字一改（比如报销时限从 15 天改成 20 天），
     * 题目的关键词就过期了，评估会永远判错。</p>
     */
    @Test
    void everyExpectedKeywordShouldAppearInItsDoc() throws Exception {
        Assumptions.assumeTrue(evalResourcesAvailable(), "评估资源不存在，跳过");

        Map<String, String> corpus = loadCorpus();
        List<String[]> rows = loadGoldenSet();

        List<String> mismatches = new ArrayList<String>();
        for (String[] row : rows) {
            String id = row[0];
            String expectedDoc = row[2];
            String keywords = row[3];
            String doc = corpus.get(expectedDoc);
            if (doc == null) {
                continue; // 由上一条测试负责报错
            }
            String normalizedDoc = normalizeDigits(doc);
            for (String keyword : keywords.split("\\s+")) {
                String kw = keyword.trim();
                if (kw.isEmpty()) {
                    continue;
                }
                if (!normalizedDoc.contains(normalizeDigits(kw))) {
                    mismatches.add(id + " 的关键词「" + kw + "」不在 " + expectedDoc + " 中");
                }
            }
        }
        assertTrue(mismatches.isEmpty(),
                "黄金集与语料不一致（改了文档没改题？）：\n" + String.join("\n", mismatches));
    }

    /** 题目不能重复：重复题会把指标往同一类问题倾斜 */
    @Test
    void questionsShouldBeUnique() throws Exception {
        Assumptions.assumeTrue(evalResourcesAvailable(), "评估资源不存在，跳过");

        Set<String> seen = new HashSet<String>();
        List<String> duplicates = new ArrayList<String>();
        for (String[] row : loadGoldenSet()) {
            String question = row[1];
            if (!seen.add(question)) {
                duplicates.add(row[0] + " -> " + question);
            }
        }
        assertTrue(duplicates.isEmpty(), "黄金集存在重复题目：" + duplicates);
    }

    /** CSV 必须恰好 5 列；字段内含逗号会把数据挤错列 */
    @Test
    void csvShouldHaveFiveColumnsPerRow() throws Exception {
        Assumptions.assumeTrue(evalResourcesAvailable(), "评估资源不存在，跳过");

        List<String> badRows = new ArrayList<String>();
        for (String[] row : loadGoldenSet()) {
            if (row.length != 5) {
                badRows.add("列数=" + row.length + " -> " + row[0]);
            }
        }
        assertTrue(badRows.isEmpty(),
                "CSV 列数异常（字段里的逗号需要加引号转义）：" + badRows);
    }

    /** 评估集规模应达到可用量级：太少（<30 题）时指标会被个别题目主导 */
    @Test
    void goldenSetShouldBeLargeEnoughToBeMeaningful() throws Exception {
        Assumptions.assumeTrue(evalResourcesAvailable(), "评估资源不存在，跳过");

        int size = loadGoldenSet().size();
        assertTrue(size >= 30,
                "评估集只有 " + size + " 题，少于 30 题时指标易被个别题目主导");
    }

    /** 题目应覆盖全部语料文档，否则某些文档的检索质量从未被验证 */
    @Test
    void goldenSetShouldCoverEveryDocument() throws Exception {
        Assumptions.assumeTrue(evalResourcesAvailable(), "评估资源不存在，跳过");

        Set<String> covered = new HashSet<String>();
        for (String[] row : loadGoldenSet()) {
            covered.add(row[2]);
        }
        Set<String> all = loadCorpus().keySet();
        Set<String> uncovered = new HashSet<String>(all);
        uncovered.removeAll(covered);

        assertTrue(uncovered.isEmpty(), "以下文档没有任何对应题目：" + uncovered);
        assertFalse(all.isEmpty(), "语料目录为空");
        assertEquals(all.size(), covered.size(), "题目覆盖的文档数应等于语料文档数");
    }
}
