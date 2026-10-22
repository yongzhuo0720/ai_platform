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
    private List<KbSearchItem> items = new ArrayList<KbSearchItem>();
}
