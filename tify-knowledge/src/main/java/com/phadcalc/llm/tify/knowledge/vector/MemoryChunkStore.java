package com.phadcalc.llm.tify.knowledge.vector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存向量存储（本地 mock / 无 pgvector 时的兜底）。
 * 同样做真实余弦相似度检索，便于本地开发验证 RAG 链路。
 */
@Slf4j
@Repository
@ConditionalOnExpression("'${spring.pgvector.host:}' == ''")
public class MemoryChunkStore implements ChunkVectorStore {

    private record StoredChunk(Long id, Long documentId, Long knowledgeBaseId,
                               int chunkIndex, String content, int tokenCount, float[] embedding) {
    }

    private final Map<Long, StoredChunk> chunks = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(1);

    @Override
    public void saveChunk(Long documentId, Long knowledgeBaseId, int chunkIndex,
                          String content, int tokenCount, float[] embedding) {
        long id = idGen.incrementAndGet();
        chunks.put(id,
                new StoredChunk(id, documentId, knowledgeBaseId, chunkIndex, content, tokenCount, embedding));
    }

    @Override
    public List<ChunkHit> search(Long knowledgeBaseId, float[] queryEmbedding, int topK) {
        return chunks.values().stream()
                .filter(c -> c.knowledgeBaseId().equals(knowledgeBaseId))
                .sorted(Comparator.comparingDouble(c -> -cosine(c.embedding(), queryEmbedding)))
                .limit(topK)
                .map(c -> new ChunkHit(c.id(), c.documentId(), c.chunkIndex(), c.content(), c.tokenCount(),
                        cosine(c.embedding(), queryEmbedding)))
                .toList();
    }

    @Override
    public List<ChunkHit> listByDocument(Long documentId) {
        return chunks.values().stream()
                .filter(c -> c.documentId().equals(documentId))
                .sorted(Comparator.comparingInt(StoredChunk::chunkIndex))
                .map(c -> new ChunkHit(c.id(), c.documentId(), c.chunkIndex(), c.content(), c.tokenCount(), null))
                .toList();
    }

    @Override
    public void deleteByDocument(Long documentId) {
        List<Long> ids = chunks.values().stream()
                .filter(c -> c.documentId().equals(documentId))
                .map(StoredChunk::id)
                .toList();
        ids.forEach(chunks::remove);
    }

    @Override
    public void deleteByKnowledgeBase(Long knowledgeBaseId) {
        List<Long> ids = chunks.values().stream()
                .filter(c -> c.knowledgeBaseId().equals(knowledgeBaseId))
                .map(StoredChunk::id)
                .toList();
        ids.forEach(chunks::remove);
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
