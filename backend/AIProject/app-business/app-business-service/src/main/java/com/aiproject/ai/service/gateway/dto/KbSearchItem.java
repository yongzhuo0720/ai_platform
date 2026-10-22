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
    /** 余弦相似度 0-1，保留 4 位 */
    private double score;
}
