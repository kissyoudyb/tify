package com.phadcalc.llm.tify.knowledge.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 向量嵌入模型配置（OpenAI 兼容 /v1/embeddings 接口）。
 * 可通过环境变量覆盖：EMBEDDING_BASE_URL / EMBEDDING_MODEL / EMBEDDING_API_KEY / EMBEDDING_BATCH_SIZE。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "tify.embedding")
public class EmbeddingProperties {

    private String baseUrl = "http://192.168.130.220:8004";

    private String model = "Qwen3-Embedding-0.6B";

    /** 可选，一般不需要 */
    private String apiKey = "";

    /** 每批最多向量化的文本条数 */
    private int batchSize = 32;
}
