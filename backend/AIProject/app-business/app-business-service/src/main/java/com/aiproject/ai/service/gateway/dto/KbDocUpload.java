package com.aiproject.ai.service.gateway.dto;

import lombok.Data;

/** 本地知识库上传请求（content 为前端读取的纯文本内容） */
@Data
public class KbDocUpload {
    private String name;
    private String kbName;
    private String content;
}
