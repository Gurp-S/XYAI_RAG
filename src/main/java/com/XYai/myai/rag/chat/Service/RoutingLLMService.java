package com.XYai.myai.rag.chat.Service;

import com.XYai.myai.rag.aop.Annotation.RagTraceNode;
import com.XYai.myai.rag.chat.POJO.ModelHealthStore;
import com.XYai.myai.rag.chat.POJO.ModelRoutingExecutor;
import com.XYai.myai.rag.chat.POJO.ModelSelector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;

@Slf4j
@Service
@Primary  // 注入优先使用这个实现，覆盖其他LLMService实现
public class RoutingLLMService implements LLMService {

    // 注入三个核心组件
    private  ModelSelector selector;           // 模型选择器

    private  ModelHealthStore healthStore;     // 健康状态存储（断路器）

    private  ModelRoutingExecutor executor;     // 路由执行器

    private  Map<String, ChatClient> clientsByProvider;  // 各提供商客户端（key：provider，value：客户端）

//    public RoutingLLMService(ModelSelector selector, ModelHealthStore modelHealthStore,
//                             ModelRoutingExecutor modelRoutingExecutor, Map<String, ChatClient> clientsByProvider){
//        this.selector = selector;this.healthStore=modelHealthStore;
//        this.executor=modelRoutingExecutor;this.clientsByProvider = clientsByProvider;
//    }


    /**
     * 同步聊天请求（非流式）
     * 核心：调用executor.executeWithFallback，实现失败降级
     */
    @Override
    @RagTraceNode(name = "llm-chat-routing", type = "LLM_ROUTING")
    public String chat() {
        // 同步调用：使用 executeWithFallback 自动处理降级
        // 能力类型（此处为聊天）
        // 选择候选模型：根据请求是否需要深度思考，筛选合适的模型
        // 如何获取对应模型的客户端（根据provider匹配
        // 如何执行模型调用（调用客户端的chat方法）
        return null;
    }


    /**
     * 流式聊天请求（核心：首包探测，避免用户等待过久）
     * 核心：遍历候选模型，尝试调用，首包超时/失败则切换下一个
     */
    @Override
    @RagTraceNode(name = "llm-stream-routing", type = "LLM_ROUTING")
    public Flux<String> streamChat() {
        // 1. 选择候选模型列表

        // 2. 遍历候选模型，尝试流式调用

            // 获取当前模型的客户端

            // 3. 首包探测：等待首包响应（避免模型卡住，用户长时间等待）

            // 缓冲回调：首包成功前，缓冲所有事件，避免失败模型的内容污染输出

            // 4. 尝试发起流式调用
            //chatModel.stream(prompt);
            // 5. 等待首包（60秒超时，可配置）
            // 6. 判断首包结果
                // 首包成功：标记模型健康，返回调用句柄（后续内容通过回调推送）

            // 首包失败：标记模型失败，取消当前调用，切换下一个模型

        // 所有模型都失败了，抛出异常
        return null;
    }

    // 辅助方法：解析模型客户端（省略实现）
    private ChatClient resolveClient() {
        return null;
    }
}