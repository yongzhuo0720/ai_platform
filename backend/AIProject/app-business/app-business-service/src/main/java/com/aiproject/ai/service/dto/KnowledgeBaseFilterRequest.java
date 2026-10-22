package com.aiproject.ai.service.dto;

import lombok.Data;
import java.util.List;

@Data
public class KnowledgeBaseFilterRequest {
    String context;
    List<String> kbIds;
}
