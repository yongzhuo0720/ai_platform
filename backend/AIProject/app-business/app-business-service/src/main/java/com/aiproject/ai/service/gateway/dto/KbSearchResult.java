package com.aiproject.ai.service.gateway.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 本地知识库检索结果 */
@Data
public class KbSearchResult {
    private String query;
    /** 命中的向量化模型名 */
    private String model;
    /** 参与比对的总块数 */
    private int scanned;
    /**
     * 向量路被跳过的块数：向量维度与当前模型不一致，或向量数据损坏。
     * 切换向量模型后最容易出现，此前是静默跳过，用户只会觉得「检索变差了」。
     */
    private int skipped;
    /** 面向用户的降级说明，正常检索时为 null */
    private String warning;
    private List<KbSearchItem> items = new ArrayList<KbSearchItem>();
}
