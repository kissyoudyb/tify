package com.phadcalc.llm.tify.knowledge.vector;

import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * pgvector 存储实现（生产环境，spring.pgvector.host 非空时生效）。
 * 使用 HNSW 索引 + 余弦距离（<=>）做近似最近邻检索。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
@ConditionalOnExpression("'${spring.pgvector.host:}' != ''")
public class PgVectorChunkStore implements ChunkVectorStore {

    @Qualifier("pgvectorJdbcTemplate")
    private final JdbcTemplate pgvectorJdbcTemplate;

    @Override
    public void saveChunk(Long documentId, Long knowledgeBaseId, int chunkIndex,
                          String content, int tokenCount, float[] embedding) {
        pgvectorJdbcTemplate.update(
                "INSERT INTO document_chunk (document_id, knowledge_base_id, chunk_index, content, token_count, embedding) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                documentId, knowledgeBaseId, chunkIndex, content, tokenCount, new PGvector(embedding));
    }

    @Override
    public List<ChunkHit> search(Long knowledgeBaseId, float[] queryEmbedding, int topK) {
        return pgvectorJdbcTemplate.query(
                "SELECT id, document_id, chunk_index, content, token_count, "
                        + "1 - (embedding <=> ?) AS score "
                        + "FROM document_chunk WHERE knowledge_base_id = ? "
                        + "ORDER BY embedding <=> ? LIMIT ?",
                (rs, rowNum) -> new ChunkHit(
                        rs.getLong("id"),
                        rs.getLong("document_id"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        rs.getInt("token_count"),
                        rs.getDouble("score")),
                new PGvector(queryEmbedding), knowledgeBaseId, new PGvector(queryEmbedding), topK);
    }

    @Override
    public List<ChunkHit> listByDocument(Long documentId) {
        return pgvectorJdbcTemplate.query(
                "SELECT id, document_id, chunk_index, content, token_count, NULL AS score "
                        + "FROM document_chunk WHERE document_id = ? ORDER BY chunk_index",
                (rs, rowNum) -> new ChunkHit(
                        rs.getLong("id"),
                        rs.getLong("document_id"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        rs.getInt("token_count"),
                        null),
                documentId);
    }

    @Override
    public void deleteByDocument(Long documentId) {
        pgvectorJdbcTemplate.update("DELETE FROM document_chunk WHERE document_id = ?", documentId);
    }

    @Override
    public void deleteByKnowledgeBase(Long knowledgeBaseId) {
        pgvectorJdbcTemplate.update("DELETE FROM document_chunk WHERE knowledge_base_id = ?", knowledgeBaseId);
    }
}
