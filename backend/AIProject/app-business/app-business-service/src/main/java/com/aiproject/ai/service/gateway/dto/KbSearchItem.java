package com.aiproject.ai.service.gateway.dto;

import lombok.Data;

/** 单条检索命中 */
@Data
public class KbSearchItem {
    private Long docIid;
    private String docName;
    private String kbName;
    private Integer chunkIndex;
    private String content;

    /**
     * 融合后的排序分（RRF），仅用于排序，不是相似度。
     *
     * <p>混合检索下不存在单一「相似度」：一个片段可能向量排第 3、关键词排第 1，
     * 另一个可能反过来。RRF 分就是为这种多路排序融合设计的，
     * 因此这里放融合分而不是余弦分。</p>
     */
    private double score;

    /** 向量路（余弦相似度 0-1）；该片段未参与向量路时为 -1 */
    private double denseScore = -1D;

    /** 关键词路（词项重叠数）；该片段未参与关键词路时为 -1 */
    private int lexicalScore = -1;

    /**
     * 命中来源：{@code both} 两路都中、{@code dense} 仅向量、
     * {@code lexical} 仅关键词。
     *
     * <p>暴露这个字段是为了可解释——排查「为什么这条没被检索到」时，
     * 一眼能看出是向量没匹配上还是关键词没匹配上。</p>
     */
    private String channel = "dense";
}
