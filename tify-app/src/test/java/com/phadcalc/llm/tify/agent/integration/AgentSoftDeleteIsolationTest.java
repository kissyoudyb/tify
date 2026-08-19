package com.phadcalc.llm.tify.agent.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.phadcalc.llm.tify.app.TifyApplication;
import com.phadcalc.llm.tify.agent.dto.AgentCreateRequest;
import com.phadcalc.llm.tify.agent.dto.AgentDetailResponse;
import com.phadcalc.llm.tify.agent.dto.AgentListItem;
import com.phadcalc.llm.tify.agent.dto.AgentQueryRequest;
import com.phadcalc.llm.tify.agent.entity.Agent;
import com.phadcalc.llm.tify.agent.entity.AgentTool;
import com.phadcalc.llm.tify.agent.mapper.AgentMapper;
import com.phadcalc.llm.tify.agent.mapper.AgentToolMapper;
import com.phadcalc.llm.tify.agent.service.AgentService;
import com.phadcalc.llm.tify.chat.dto.SessionCreateRequest;
import com.phadcalc.llm.tify.chat.service.ChatService;
import com.phadcalc.llm.tify.common.dto.PageResult;
import com.phadcalc.llm.tify.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试 · B5 · Agent 软删穿透保护
 *
 * 对应 docs/critical-paths.md 链路 7（CLAUDE.md 风险：应用层维护外键，软删后跨模块保护）。
 * 本测试锁住两条已正确实现的契约：
 *   - delete(id) 后 getDetail(id) 必须返回 null（@TableLogic 已生效）
 *   - delete(id) 后 list() 必须排除该 agent（@TableLogic SQL 生效）
 *   - delete(id) 后 ChatService.createSession(agentId) 必须抛 AGENT_NOT_FOUND
 *
 * 当前未锁的已知风险（CLAUDE.md 红线）：
 *   - delete(id) 后 listSessions(agentId) 仍返回旧 session（chat_session 无 join agent 的软删过滤）
 *   - 见 https://github.com/kissyoudyb/tify/issues 待补充
 */
@SpringBootTest(
        classes = TifyApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
class AgentSoftDeleteIsolationTest {

    @Autowired AgentService agentService;
    @Autowired ChatService chatService;
    @Autowired AgentMapper agentMapper;
    @Autowired AgentToolMapper agentToolMapper;
    @Autowired CacheManager cacheManager;

    private Agent inserted;

    @BeforeEach
    void setUp() {
        // 清 agent-cache 避免 @Cacheable 跨测试污染
        var cache = cacheManager.getCache("agent-cache");
        if (cache != null) cache.clear();

        Agent agent = new Agent();
        agent.setName("b5-agent-" + System.nanoTime());
        agent.setDescription("soft-delete test");
        agent.setSystemPrompt("you are a tester");
        agent.setModelConfigId(1L);  // 依赖 K8s MySQL 现有的 model_config id=1
        agent.setTemperature(new BigDecimal("0.70"));
        agent.setMaxTokens(1024);
        agent.setMaxContextTurns(5);
        agent.setEnabled(1);
        agentMapper.insert(agent);
        this.inserted = agent;
    }

    @AfterEach
    void tearDown() {
        // 防御性：delete(id) 走的是逻辑删，DB 行还在，再用 deleteById 强删
        if (inserted != null && inserted.getId() != null) {
            try {
                agentToolMapper.delete(
                    new LambdaQueryWrapper<AgentTool>().eq(AgentTool::getAgentId, inserted.getId()));
                agentMapper.deleteById(inserted.getId());
                // 兜底：万一逻辑删没生效
                agentMapper.delete(
                    new LambdaQueryWrapper<Agent>().eq(Agent::getId, inserted.getId()));
            } catch (Exception ignored) {}
        }
        var cache = cacheManager.getCache("agent-cache");
        if (cache != null) cache.clear();
    }

    @Test
    @DisplayName("#5a · delete(id) 后 getDetail(id) 必须抛 AGENT_NOT_FOUND")
    void deleteAfter_getDetailReturnsNull() {
        // 先确认 insert 后能查到
        AgentDetailResponse before = agentService.getDetail(inserted.getId());
        assertNotNull(before, "新建后应能查到");
        assertEquals(inserted.getId(), before.getId());

        // 软删
        agentService.delete(inserted.getId());

        // 期望：@TableLogic 让 selectById 过滤 deleted=1，getOrThrow 抛 AGENT_NOT_FOUND
        BizException ex = assertThrows(BizException.class,
                () -> agentService.getDetail(inserted.getId()));
        assertTrue(ex.getMessage().contains("Agent"),
                "期望错误消息含 Agent: " + ex.getMessage());
    }

    @Test
    @DisplayName("#5b · delete(id) 后 list() 不应再包含该 agent（@TableLogic 在 SQL 阶段生效）")
    void deleteAfter_listExcludesDeleted() {
        PageResult<AgentListItem> before = agentService.list(new AgentQueryRequest());

        // 找到我刚 insert 的 agent
        AgentListItem myRow = before.getList().stream()
            .filter(a -> a.getId().equals(inserted.getId()))
            .findFirst().orElseThrow(() -> new AssertionError("list() 应包含新插入的 agent"));

        agentService.delete(inserted.getId());

        // 再次 list
        PageResult<AgentListItem> after = agentService.list(new AgentQueryRequest());

        boolean stillThere = after.getList().stream()
            .anyMatch(a -> a.getId().equals(inserted.getId()));
        assertTrue(!stillThere, "list() 应对软删 agent 不可见");
    }

    @Test
    @DisplayName("#5c · delete(id) 后 ChatService.createSession(agentId) 必须抛 AGENT_NOT_FOUND")
    void deleteAfter_createSessionRejects() {
        // 软删
        agentService.delete(inserted.getId());

        // delete 之后 createSession 应被拒绝
        SessionCreateRequest req = new SessionCreateRequest();
        req.setAgentId(inserted.getId());
        req.setTitle("should-fail");

        BizException ex = assertThrows(BizException.class, () -> chatService.createSession(req));
        assertTrue(ex.getMessage().contains("Agent") || ex.getMessage().contains("Agent"), "期望错误消息含 Agent: " + ex.getMessage());
    }
}
