package com.aiproject.ai.service.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 评估用例关键词/命中文档列表的 JSON 编解码，Holder 与 Runner 共用一套口径。 */
public final class RagEvalKeywordCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RagEvalKeywordCodec() {
    }

    public static String writeKeywords(List<String> keywords) {
        try {
            return MAPPER.writeValueAsString(keywords == null
                    ? Collections.emptyList() : keywords);
        } catch (Exception e) {
            return "[]";
        }
    }

    public static List<String> parseKeywords(String json) {
        List<String> list = parseStringList(json);
        if (list.isEmpty() && json != null && !json.trim().isEmpty()
                && !json.trim().startsWith("[")) {
            // 兼容手工导入的纯文本关键词（空格分隔），JSON 解析失败时不让关键词静默丢失
            for (String part : json.trim().split("\\s+")) {
                if (!part.isEmpty()) {
                    list.add(part);
                }
            }
        }
        return list;
    }

    public static String writeStringList(List<String> values) {
        try {
            return MAPPER.writeValueAsString(values == null
                    ? Collections.emptyList() : values);
        } catch (Exception e) {
            return "[]";
        }
    }

    public static List<String> parseStringList(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<String>();
        }
        try {
            List<String> list = MAPPER.readValue(json, new TypeReference<List<String>>() {});
            return list == null ? new ArrayList<String>() : list;
        } catch (Exception e) {
            return new ArrayList<String>();
        }
    }
}
