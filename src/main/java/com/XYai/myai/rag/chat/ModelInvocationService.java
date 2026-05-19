package com.XYai.myai.rag.chat;

import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.chat.pojo.ChatMessage;
import com.XYai.myai.rag.chat.pojo.StreamResult;
import com.XYai.myai.rag.memory.ConversationMemorySummaryService;
import com.XYai.myai.xyAdmin.mapper.SystemConfigMapper;
import com.XYai.myai.xyAdmin.mapper.TokenRecordMapper;
import com.XYai.myai.xyAdmin.pojo.SystemConfig;
import com.XYai.myai.xyAdmin.pojo.TokenRecord;
import com.XYai.myai.xyAdmin.service.SystemConfigService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 模型调用服务
 * 步骤9-11：调用模型、保存记忆
 */
@Slf4j
@Component
public class ModelInvocationService {

    private static final String SYSTEM_MESSAGE = """
            你叫XY一个专业活泼可爱的AI
            1. 回答要求：准确、逻辑清晰、简洁避免废话和格式化
            2. 风格：闲聊时亲和有趣，解答问题时严谨专业
            3. 工具：基于工具结果回答，无关结果时说明并给出常识性答案
            4. 参考资料：作为辅助，无关忽略，仅精炼引用关键信息
            5. 用户提交的文件:
            6. 语言：通俗易懂、自然流畅，拒绝生硬回答
            """;

    @Resource
    private ModelRouterService modelRouterService;

    @Resource
    private TokenRecordMapper tokenRecordMapper;

    @Resource
    private ConversationMemorySummaryService conversationMemorySummaryService;

    @Resource
    private SystemConfigMapper systemConfigMapper;

    @Resource(name = "memeryExecutor")
    private ThreadPoolTaskExecutor memoryExecutor;

    // ==================== 步骤9：调用模型（流式，自动降级） ====================

    @RagTraceNode(name = "模型路由流式调用", type = "模型路由")
    public StreamResult callModelStream(String finalPrompt, String conversationId) {
        log.debug("调用模型路由，conversationId: {}", conversationId);
        return modelRouterService.routeStream(finalPrompt, conversationId);
    }

    // ==================== 步骤10：调用模型快速模式（并发） ====================

    @RagTraceNode(name = "快速模式路由调用", type = "模型路由")
    public StreamResult callModelFastStream(String finalPrompt, String conversationId) {
        log.debug("快速模式调用模型，conversationId: {}", conversationId);
        return modelRouterService.routeFastStream(finalPrompt, conversationId);
    }

    // ==================== 步骤11：异步保存对话记忆 ====================

    @RagTraceNode(name = "保存对话记忆", type = "记忆保存")
    public void saveMemoryAsync(
            String conversationId,
            String chatMessageId,
            String userMessage,
            String assistantMessage,
            Long userId) {

        if (userId == null || conversationId == null) {
            log.debug("跳过保存记忆：userId或conversationId为空");
            CompletableFuture.completedFuture(null);
            return;
        }

        ChatMessage chatMsg = ChatMessage.builder()
                .chatMessageId(chatMessageId)
                .userMessage(userMessage)
                .assistantMessage(assistantMessage)
                .userId(userId)
                .build();

        CompletableFuture.runAsync(() -> {
            try {
                conversationMemorySummaryService.compressIfNeeded(conversationId, chatMsg);
                log.debug("记忆保存成功，conversationId={}, chatMessageId={}", conversationId, chatMessageId);
            } catch (Exception e) {
                log.error("记忆保存失败: {}", e.getMessage(), e);
            }
        }, memoryExecutor);
    }

    // ==================== 步骤11：异步保存对话记忆 ====================

    public void saveTokenUseAsync(
            String conversationId,
            String chatMessageId,
            Long promptTokens,
            Long completionTokens,
            Long userId,
            Long costMs,
            String modelName,
            String callType) {

        if (userId == null || conversationId == null) {
            log.debug("跳过Token消耗：userId或conversationId为空");
            CompletableFuture.completedFuture(null);
            return;
        }
        long pt = promptTokens != null ? promptTokens : 0L;
        long ct = completionTokens != null ? completionTokens : 0L;

        CompletableFuture.runAsync(() -> {
            try {
                // "chat_default" -> "默认对话模型";
                SystemConfig systemConfig = systemConfigMapper.selectOne(
                    new QueryWrapper<SystemConfig>().eq("config_key", modelName));
                tokenRecordMapper.insert(TokenRecord.builder()
                        .chatMessageId(chatMessageId)
                        .conversationId(conversationId)
                        .userId(userId)
                        .modelName(modelName==null?systemConfig.getConfigValue():modelName)
                        .promptTokens((int) pt)
                        .completionTokens((int) ct)
                        .totalTokens((int) (pt + ct))
                        .costMs(costMs)
                        .callType(callType != null ? callType : "chat")
                        .createdAt(LocalDateTime.now())
                        .build());
                log.debug("Token消耗保存成功，conversationId={}, msgId={}", conversationId, chatMessageId);
            } catch (Exception e) {
                log.error("Token消耗保存失败: {}", e.getMessage(), e);
            }
        }, memoryExecutor);
    }

    // ==================== 辅助方法 ====================

    public String getSystemMessage() {
        return SYSTEM_MESSAGE;
    }

    public Map<String, Object> getHealthStatus() {
        return modelRouterService.getHealthStatus();
    }
}