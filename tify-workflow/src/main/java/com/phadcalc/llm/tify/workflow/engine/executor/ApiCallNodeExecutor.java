package com.phadcalc.llm.tify.workflow.engine.executor;

import com.phadcalc.llm.tify.workflow.dto.NodeConfigDef;
import com.phadcalc.llm.tify.workflow.engine.ExecutionContext;
import com.phadcalc.llm.tify.workflow.engine.NodeExecutor;
import com.phadcalc.llm.tify.workflow.entity.WorkflowNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class ApiCallNodeExecutor implements NodeExecutor {

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    @Override
    public String nodeType() {
        return "API_CALL";
    }

    @Override
    public void execute(WorkflowNode node, NodeConfigDef config, ExecutionContext ctx) {
        NodeConfigDef.ApiCallConfig apiConfig = (NodeConfigDef.ApiCallConfig) config;

        String url = ctx.resolve(apiConfig.url() != null ? apiConfig.url() : "");
        String method = apiConfig.method() != null ? apiConfig.method().toUpperCase() : "GET";
        String outputVar = apiConfig.outputVariable() != null ? apiConfig.outputVariable() : "response";

        log.info("ApiCallNodeExecutor node={} {} {}", node.getNodeKey(), method, url);

        Request request = new Request.Builder()
                .url(url)
                .method(method, null)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP " + response.code());
            }
            String body = response.body() != null ? response.body().string() : "";
            ctx.set(node.getNodeKey(), outputVar, body);
        } catch (Exception e) {
            // 失败向上抛，由 WorkflowEngine 统一标记 FAILED，避免静默写错误文案误导后续节点
            log.error("ApiCallNodeExecutor failed node={}: {}", node.getNodeKey(), e.getMessage());
            throw new RuntimeException("节点 [" + node.getNodeKey() + "] API 调用失败: " + e.getMessage(), e);
        }
    }
}
