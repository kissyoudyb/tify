package com.phadcalc.llm.tify.knowledge.vector;

import java.util.List;

/**
 * 文档分块向量存储抽象。
 * 生产走 pgvector（PgVectorChunkStore），本地 mock（无 pgvector）走内存实现。
 */
public interface ChunkVectorStore {

    void saveChunk(Long documentId, Long knowledgeBaseId, int chunkIndex,
                   String content, int tokenCount, float[] embedding);

    /** 按余弦相似度检索与 queryEmbedding 最相关的 topK 个分块 */
    List<ChunkHit> search(Long knowledgeBaseId, float[] queryEmbedding, int topK);

    /** 按文档列出全部分块（chunk_index 升序） */
    List<ChunkHit> listByDocument(Long documentId);

    void deleteByDocument(Long documentId);

    void deleteByKnowledgeBase(Long knowledgeBaseId);
}
