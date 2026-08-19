package com.phadcalc.llm.tify.knowledge.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.phadcalc.llm.tify.common.http.LlmApiException;
import com.phadcalc.llm.tify.common.http.LlmHttpClient;
import com.phadcalc.llm.tify.knowledge.config.EmbeddingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 调用 OpenAI 兼容的 /v1/embeddings 接口，将文本批量向量化。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingClient {

    private final LlmHttpClient llmHttpClient;
    private final EmbeddingProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 批量向量化，自动按 batchSize 分批 */
    public List<float[]> embed(List<String> texts) {
        List<float[]> all = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += props.getBatchSize()) {
            List<String> batch = texts.subList(i, Math.min(i + props.getBatchSize(), texts.size()));
            all.addAll(embedBatch(batch));
        }
        return all;
    }

    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    /**
     * 检索查询向量化。
     * 注：Qwen3-Embedding-0.6B 实测加 "Instruct:" 指令前缀反而降低相似度，
     * 与文档段落保持一致（无前缀）效果更好。
     */
    public float[] embedQuery(String query) {
        return embed(query);
    }

    private List<float[]> embedBatch(List<String> texts) {
        try {
            String body = objectMapper.writeValueAsString(Map.of("input", texts, "model", props.getModel()));
            Map<String, String> headers = (props.getApiKey() == null || props.getApiKey().isBlank())
                    ? Map.of()
                    : Map.of("Authorization", "Bearer " + props.getApiKey());
            long start = System.currentTimeMillis();
            String resp = llmHttpClient.post(props.getBaseUrl() + "/v1/embeddings", headers, body);
            log.info("embedding batch texts={} cost={}ms", texts.size(), System.currentTimeMillis() - start);

            JsonNode root = objectMapper.readTree(resp);
            JsonNode data = root.path("data");
            List<float[]> result = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode emb = item.path("embedding");
                float[] v = new float[emb.size()];
                for (int k = 0; k < emb.size(); k++) {
                    v[k] = (float) emb.get(k).asDouble();
                }
                result.add(v);
            }
            if (result.size() != texts.size()) {
                throw new LlmApiException(LlmApiException.Type.UNKNOWN, -1,
                        "Embedding 返回数量不一致: 请求=" + texts.size() + " 返回=" + result.size());
            }
            return result;
        } catch (LlmApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("embedding 调用失败: {}", e.getMessage());
            throw new LlmApiException(LlmApiException.Type.UNKNOWN, -1, "Embedding 调用失败: " + e.getMessage());
        }
    }
}
