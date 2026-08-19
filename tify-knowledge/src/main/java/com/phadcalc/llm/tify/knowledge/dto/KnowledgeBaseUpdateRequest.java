package com.phadcalc.llm.tify.knowledge.dto;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class KnowledgeBaseUpdateRequest {
    private String name;
    private String description;
    private Integer enabled;
}
