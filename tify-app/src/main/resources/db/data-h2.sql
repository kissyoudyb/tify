-- mock profile 测试数据，仅用于本地开发验证
-- 幂等写法：同一 H2 内存库可能被多个 Spring context 复用（如 @MockBean 差异导致 context 缓存 key 变化），重复执行不报错

INSERT INTO provider(name, type, base_url, auth_config, description, enabled, deleted, created_at, updated_at)
SELECT 'OpenAI', 'OPENAI', 'https://api.openai.com', '{"apiKey":"sk-test"}', '', 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM provider WHERE id = 1);

INSERT INTO model_config(provider_id, name, model_id, context_size, enabled, deleted, created_at, updated_at)
SELECT 1, 'GPT-4o', 'gpt-4o', 128000, 1, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM model_config WHERE id = 1);

INSERT INTO agent(name, description, model_config_id, system_prompt, temperature, max_tokens, deleted, created_at, updated_at)
SELECT 'Test Agent', 'mock测试用Agent', 1, 'You are a helpful assistant.', 0.7, 2048, 0, NOW(), NOW()
WHERE NOT EXISTS (SELECT 1 FROM agent WHERE id = 1);
