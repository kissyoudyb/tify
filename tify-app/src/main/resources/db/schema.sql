-- Tify DDL
-- 字符集：utf8mb4，主键 bigint 自增，逻辑删除 deleted tinyint(1)
-- 外键在应用层维护，不建数据库级外键约束

CREATE DATABASE IF NOT EXISTS tify DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE tify;

-- ─────────────────────────────────────────────
-- 模型提供商
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS provider (
    id          BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(100)    NOT NULL                COMMENT '提供商展示名称，唯一',
    type        VARCHAR(30)     NOT NULL                COMMENT '类型：OPENAI / ANTHROPIC / OLLAMA / AZURE_OPENAI / OPENAI_COMPATIBLE',
    base_url    VARCHAR(500)    NOT NULL                COMMENT 'API 基础地址',
    auth_config JSON            NOT NULL                COMMENT '鉴权配置，结构随 type 变化',
    description VARCHAR(500)    DEFAULT ''              COMMENT '备注',
    enabled     TINYINT(1)      NOT NULL DEFAULT 1      COMMENT '是否启用：1 启用 0 禁用',
    deleted     TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常 1 删除',
    created_at  DATETIME        NOT NULL                COMMENT '创建时间',
    updated_at  DATETIME        NOT NULL                COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY idx_provider_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型提供商';

-- ─────────────────────────────────────────────
-- 模型配置（隶属于提供商）
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS model_config (
    id           BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    provider_id  BIGINT          NOT NULL                COMMENT '所属提供商 id',
    name         VARCHAR(100)    NOT NULL                COMMENT '模型展示名称，如 GPT-4o',
    model_id     VARCHAR(100)    NOT NULL                COMMENT '调用时传给 API 的模型标识，Azure 为 deployment name',
    context_size INT             NOT NULL DEFAULT 4096   COMMENT '上下文窗口大小（token）',
    extra_params JSON                                    COMMENT '模型级扩展参数，如 {"maxTokens":4096}',
    enabled      TINYINT(1)      NOT NULL DEFAULT 1      COMMENT '是否启用',
    deleted      TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    created_at   DATETIME        NOT NULL                COMMENT '创建时间',
    updated_at   DATETIME        NOT NULL                COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_model_config_provider_id (provider_id),
    UNIQUE KEY uk_model_config_provider_model (provider_id, model_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型配置';

-- ─────────────────────────────────────────────
-- 供应商健康状态（高频写入，独立表）
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS provider_health (
    id              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    provider_id     BIGINT          NOT NULL                COMMENT '关联 provider.id',
    status          VARCHAR(20)     NOT NULL DEFAULT 'UNKNOWN' COMMENT 'UP / DOWN / DEGRADED / UNKNOWN',
    last_check_at   DATETIME                                COMMENT '最后探测时间',
    last_success_at DATETIME                                COMMENT '最后成功时间',
    fail_count      INT             NOT NULL DEFAULT 0      COMMENT '连续失败次数',
    latency_ms      INT                                     COMMENT '最近一次延迟（ms）',
    error_message   VARCHAR(500)                            COMMENT '最近失败原因',
    updated_at      DATETIME        NOT NULL                COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY idx_provider_health_provider_id (provider_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='供应商健康状态';

-- ─────────────────────────────────────────────
-- MCP Server（工具服务）
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS mcp_server (
    id           BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    name         VARCHAR(100)    NOT NULL                COMMENT 'MCP Server 名称',
    endpoint     VARCHAR(500)    NOT NULL                COMMENT '服务端点地址',
    description  VARCHAR(500)    DEFAULT ''              COMMENT '描述',
    enabled      TINYINT(1)      NOT NULL DEFAULT 1      COMMENT '是否启用',
    deleted      TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    created_at   DATETIME        NOT NULL                COMMENT '创建时间',
    updated_at   DATETIME        NOT NULL                COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY idx_mcp_server_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MCP 工具服务';

-- ─────────────────────────────────────────────
-- Agent 配置
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS agent (
    id                  BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    name                VARCHAR(100)    NOT NULL                COMMENT 'Agent 名称',
    description         VARCHAR(500)    DEFAULT ''              COMMENT '描述',
    system_prompt       TEXT                                   COMMENT 'System Prompt（TEXT 不能有默认值）',
    model_config_id     BIGINT          NOT NULL                COMMENT '绑定的模型配置 id',
    temperature         DECIMAL(3,2)    NOT NULL DEFAULT 0.70   COMMENT '温度参数 0.00~1.00',
    max_tokens          INT             NOT NULL DEFAULT 2048   COMMENT '最大输出 token 数',
    max_context_turns   INT             NOT NULL DEFAULT 10     COMMENT '保留最近几轮上下文',
    enabled             TINYINT(1)      NOT NULL DEFAULT 1      COMMENT '是否启用',
    knowledge_base_id   BIGINT          DEFAULT NULL            COMMENT '绑定的知识库 id，NULL = 不启用 RAG（FK → knowledge_base.id）',
    workflow_id         BIGINT          DEFAULT NULL            COMMENT '绑定的工作流 id，NULL = 不启用工作流（FK → workflow.id）',
    deleted             TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    created_at          DATETIME        NOT NULL                COMMENT '创建时间',
    updated_at          DATETIME        NOT NULL                COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY idx_agent_name (name),
    KEY idx_agent_model_config_id (model_config_id),
    KEY idx_agent_knowledge_base_id (knowledge_base_id),
    KEY idx_agent_workflow_id (workflow_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 配置';

-- ─────────────────────────────────────────────
-- Agent 与 MCP Server 关联（M:N）
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS agent_tool (
    id            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    agent_id      BIGINT      NOT NULL                COMMENT 'Agent id',
    mcp_server_id BIGINT      NOT NULL                COMMENT 'MCP Server id',
    created_at    DATETIME    NOT NULL                COMMENT '创建时间',
    updated_at    DATETIME    NOT NULL                COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY idx_agent_tool_agent_mcp (agent_id, mcp_server_id),
    KEY idx_agent_tool_mcp_server_id (mcp_server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 绑定工具';

-- ─────────────────────────────────────────────
-- 对话会话
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat_session (
    id         BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    agent_id   BIGINT          NOT NULL                COMMENT '所属 Agent id',
    title      VARCHAR(200)    DEFAULT ''              COMMENT '会话标题',
    status     VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE / ARCHIVED',
    deleted    TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    created_at DATETIME        NOT NULL                COMMENT '创建时间',
    updated_at DATETIME        NOT NULL                COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_chat_session_agent_id (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话会话';

-- ─────────────────────────────────────────────
-- 对话消息（增长最快，注意分页查询性能）
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chat_message (
    id            BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    session_id    BIGINT          NOT NULL                COMMENT '所属会话 id',
    role          VARCHAR(20)     NOT NULL                COMMENT '消息角色：user / assistant / system',
    content       LONGTEXT        NOT NULL                COMMENT '消息内容',
    tokens        INT             DEFAULT 0               COMMENT '消耗 token 数',
    finish_reason VARCHAR(50)     DEFAULT ''              COMMENT 'LLM finish_reason',
    latency_ms    INT             DEFAULT 0               COMMENT '首 token 到流结束耗时（ms）',
    deleted       TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    created_at    DATETIME        NOT NULL                COMMENT '创建时间',
    updated_at    DATETIME        NOT NULL                COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_chat_message_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对话消息';

-- ─────────────────────────────────────────────
-- 知识库
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS knowledge_base (
    id          BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(100)    NOT NULL                COMMENT '知识库名称',
    description VARCHAR(500)    DEFAULT ''              COMMENT '描述',
    enabled     TINYINT(1)      NOT NULL DEFAULT 1      COMMENT '是否启用',
    deleted     TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    created_at  DATETIME        NOT NULL                COMMENT '创建时间',
    updated_at  DATETIME        NOT NULL                COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_knowledge_base_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库';

-- ─────────────────────────────────────────────
-- 文档
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS document (
    id               BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    knowledge_base_id BIGINT        NOT NULL                COMMENT '所属知识库 id（FK → knowledge_base.id）',
    name             VARCHAR(255)    NOT NULL                COMMENT '文件名',
    file_type        VARCHAR(50)     DEFAULT ''              COMMENT '文件类型（如 pdf / docx / md）',
    file_size        BIGINT          NOT NULL DEFAULT 0      COMMENT '字节数',
    status           VARCHAR(20)     NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING / PROCESSING / DONE / FAILED',
    error_message    VARCHAR(1000)   DEFAULT ''              COMMENT '处理失败原因',
    chunk_count      INT             NOT NULL DEFAULT 0      COMMENT '切分块数',
    deleted          TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除',
    created_at       DATETIME        NOT NULL                COMMENT '创建时间',
    updated_at       DATETIME        NOT NULL                COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_document_kb_id (knowledge_base_id),
    KEY idx_document_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档';

-- ─────────────────────────────────────────────
-- 工作流定义
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS workflow (
    id          BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    name        VARCHAR(100)    NOT NULL                COMMENT '工作流名称',
    description VARCHAR(500)    DEFAULT ''              COMMENT '描述',
    status      VARCHAR(20)     NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT / PUBLISHED / DISABLED',
    deleted     TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常 1 删除',
    created_at  DATETIME        NOT NULL                COMMENT '创建时间',
    updated_at  DATETIME        NOT NULL                COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_workflow_name (name),
    KEY idx_workflow_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流定义';

-- ─────────────────────────────────────────────
-- 工作流节点
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS workflow_node (
    id          BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    workflow_id BIGINT          NOT NULL                COMMENT '所属工作流 id（FK → workflow.id）',
    node_key    VARCHAR(100)    NOT NULL                COMMENT '工作流内节点唯一标识（如 classify / router）',
    type        VARCHAR(50)     NOT NULL                COMMENT '节点类型：LLM / CONDITION / API_CALL / KNOWLEDGE / START / END',
    name        VARCHAR(100)    NOT NULL DEFAULT ''     COMMENT '节点展示名',
    config      TEXT                                    COMMENT '节点配置 JSON（默认 {}）',
    deleted     TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常 1 删除',
    created_at  DATETIME        NOT NULL                COMMENT '创建时间',
    updated_at  DATETIME        NOT NULL                COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY idx_workflow_node_workflow_key (workflow_id, node_key),
    KEY idx_workflow_node_workflow_id (workflow_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流节点';

-- ─────────────────────────────────────────────
-- 工作流连线
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS workflow_edge (
    id              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    workflow_id     BIGINT          NOT NULL                COMMENT '所属工作流 id（FK → workflow.id）',
    source_node_key VARCHAR(100)    NOT NULL                COMMENT '起始节点 key',
    target_node_key VARCHAR(100)    NOT NULL                COMMENT '目标节点 key',
    condition_expr  VARCHAR(500)    DEFAULT NULL            COMMENT '条件表达式，NULL 表示无条件',
    deleted         TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常 1 删除',
    created_at      DATETIME        NOT NULL                COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL                COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_workflow_edge_workflow_id (workflow_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流连线';

-- ─────────────────────────────────────────────
-- 工作流执行实例
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS workflow_run (
    id          BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    workflow_id BIGINT          NOT NULL                COMMENT '所属工作流 id（FK → workflow.id）',
    status      VARCHAR(20)     NOT NULL DEFAULT 'RUNNING' COMMENT '状态：RUNNING / SUCCESS / FAILED',
    input       TEXT                                    COMMENT '入参快照 JSON',
    output      TEXT                                    COMMENT '出参快照 JSON',
    error       VARCHAR(500)                            COMMENT '失败原因',
    elapsed_ms  INT                                     COMMENT '总耗时（ms）',
    finished_at DATETIME                                COMMENT '结束时间',
    deleted     TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常 1 删除',
    created_at  DATETIME        NOT NULL                COMMENT '创建时间',
    updated_at  DATETIME        NOT NULL                COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_workflow_run_workflow_id (workflow_id),
    KEY idx_workflow_run_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流执行实例';

-- ─────────────────────────────────────────────
-- 工作流节点执行实例
-- ─────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS workflow_node_run (
    id              BIGINT          NOT NULL AUTO_INCREMENT COMMENT '主键',
    workflow_run_id BIGINT          NOT NULL                COMMENT '所属执行实例 id（FK → workflow_run.id）',
    node_key        VARCHAR(100)    NOT NULL                COMMENT '节点 key',
    node_type       VARCHAR(50)     NOT NULL                COMMENT '节点类型（同 workflow_node.type）',
    status          VARCHAR(20)     NOT NULL DEFAULT 'RUNNING' COMMENT '状态：RUNNING / SUCCESS / FAILED',
    outputs         TEXT                                    COMMENT '节点输出快照 JSON（ctx.snapshot()）',
    error           VARCHAR(500)                            COMMENT '失败原因',
    elapsed_ms      INT                                     COMMENT '节点耗时（ms）',
    finished_at     DATETIME                                COMMENT '节点结束时间',
    deleted         TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '逻辑删除：0 正常 1 删除',
    created_at      DATETIME        NOT NULL                COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL                COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_workflow_node_run_workflow_run_id (workflow_run_id),
    KEY idx_workflow_node_run_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工作流节点执行实例';