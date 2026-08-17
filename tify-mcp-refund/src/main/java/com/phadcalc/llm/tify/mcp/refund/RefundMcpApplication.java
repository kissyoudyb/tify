package com.phadcalc.llm.tify.mcp.refund;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 退款 MCP Server 启动入口。
 *
 * <p>独立 Spring Boot 进程，监听 9001 端口，对外暴露 MCP 协议（/sse + /messages）。
 * 不依赖 tify 主工程，单独部署。
 */
@SpringBootApplication
@MapperScan("com.phadcalc.llm.tify.mcp.refund.mapper")
public class RefundMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(RefundMcpApplication.class, args);
    }
}
