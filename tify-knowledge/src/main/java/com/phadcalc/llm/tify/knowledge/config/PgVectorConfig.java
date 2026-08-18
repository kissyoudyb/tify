package com.phadcalc.llm.tify.knowledge.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * pgvector 数据源。
 * 仅当配置了 spring.pgvector.host（K8s 环境有 PGVECTOR_HOST）时创建。
 *
 * 注意：这里只暴露 JdbcTemplate，DataSource 不注册为 Spring Bean——
 * 否则 DataSourceAutoConfiguration 看到已有 DataSource 会放弃创建 MySQL 数据源，
 * 导致 MyBatis-Plus 全部指向 PostgreSQL（relation "xxx" does not exist）。
 */
@Configuration
public class PgVectorConfig {

    private HikariDataSource dataSource;

    @Bean
    @ConditionalOnExpression("'${spring.pgvector.host:}' != ''")
    public JdbcTemplate pgvectorJdbcTemplate(
            @Value("${spring.pgvector.host}") String host,
            @Value("${spring.pgvector.port:5432}") int port,
            @Value("${spring.pgvector.database:tify}") String database,
            @Value("${spring.pgvector.username:tify}") String username,
            @Value("${spring.pgvector.password:}") String password) {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setMaximumPoolSize(5);
        dataSource.setMinimumIdle(1);
        dataSource.setConnectionTimeout(5000);
        return new JdbcTemplate(dataSource);
    }

    @PreDestroy
    public void closeDataSource() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
