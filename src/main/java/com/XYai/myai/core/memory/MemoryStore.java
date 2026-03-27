package com.XYai.myai.core.memory;

import java.util.List;

/**
 * 会话记忆存储抽象。
 *
 * <p>职责：按会话 ID 管理历史对话上下文，并在历史过长时提供压缩能力，
 * 供聊天服务在构建 prompt 时复用。</p>
 *
 * <p>典型调用顺序：</p>
 * <ol>
 *   <li>调用 {@link #getContext(String)} 获取上下文。</li>
 *   <li>模型生成回复后调用 {@link #addInteraction(String, String, String)} 写入本轮对话。</li>
 *   <li>达到阈值后调用 {@link #compactConversation(String)} 压缩历史。</li>
 * </ol>
 */
public interface MemoryStore {

    /**
     * 追加一轮对话到会话记忆。
     *
     * <p>建议在模型成功返回后立即调用，保证上下文连续。</p>
     *
     * @param conversationId 会话 ID，用于隔离不同用户/会话的记忆
     * @param userUtterance 用户本轮输入
     * @param botResponse AI 本轮输出
     */
    void addInteraction(String conversationId, String userUtterance, String botResponse);

    /**
     * 读取可用于本轮推理的上下文。
     *
     * <p>建议在调用模型前执行，用于拼接 prompt 历史部分。</p>
     *
     * @param conversationId 会话 ID
     * @return 上下文文本列表；无数据时返回空列表
     */
    List<String> getContext(String conversationId);

    /**
     * 对会话进行压缩（摘要化）。
     *
     * <p>当上下文条数或 token 超阈值时调用，常见策略为“摘要 + 最近 N 轮”。</p>
     *
     * @param conversationId 会话 ID
     */
    void compactConversation(String conversationId);


    String load(String conversationId);
}
