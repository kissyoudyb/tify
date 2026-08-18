package com.phadcalc.llm.tify.knowledge.vector;

/**
 * 分块检索命中项。score 为余弦相似度（越大越相关，范围约 [-1, 1]）。
 */
public record ChunkHit(Long id, Long documentId, Integer chunkIndex,
                       String content, Integer tokenCount, Double score) {
}
