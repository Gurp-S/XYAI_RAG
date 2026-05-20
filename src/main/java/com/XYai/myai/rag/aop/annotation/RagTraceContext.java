package com.XYai.myai.rag.aop.annotation;

import com.alibaba.ttl.TransmittableThreadLocal;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * RAG 链路追踪上下文类。
 * 使用 TransmittableThreadLocal 维护 traceId 和节点调用栈，
 * 确保在异步线程池和响应式编程环境下链路信息的正确传递。
 */
public class RagTraceContext {

    // 使用 TransmittableThreadLocal 确保跨线程传递
    private static final ThreadLocal<String> TRACE_ID_HOLDER = new TransmittableThreadLocal<>();
    private static final ThreadLocal<Deque<String>> NODE_STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Deque<String>> NODE_NAMES = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<String> NODE_WARN_HOLDER = new TransmittableThreadLocal<>();
    private static final ThreadLocal<String> RUN_WARN_HOLDER = new TransmittableThreadLocal<>();

    public static void clear() {
        TRACE_ID_HOLDER.remove();
        NODE_STACK.remove();
        NODE_NAMES.remove();
        NODE_WARN_HOLDER.remove();
        RUN_WARN_HOLDER.remove();
    }

    // ==================== Warn 消息传递 ====================

    /**
     * 设置当前节点的 warn 消息，方法执行完成后由切面自动记录。
     */
    public static void setNodeWarn(String message) {
        NODE_WARN_HOLDER.set(message);
    }

    /**
     * 获取并清除当前节点 warn 消息。
     */
    public static String getAndClearNodeWarn() {
        String msg = NODE_WARN_HOLDER.get();
        NODE_WARN_HOLDER.remove();
        return msg;
    }

    /**
     * 设置当前链路级别的 warn 消息，方法执行完成后由切面自动记录。
     */
    public static void setRunWarn(String message) {
        RUN_WARN_HOLDER.set(message);
    }

    /**
     * 获取并清除当前链路级别的 warn 消息。
     */
    public static String getAndClearRunWarn() {
        String msg = RUN_WARN_HOLDER.get();
        RUN_WARN_HOLDER.remove();
        return msg;
    }

    public static String getTraceId() {
        return TRACE_ID_HOLDER.get();
    }

    public static void setTraceId(String traceId) {
        TRACE_ID_HOLDER.set(traceId);
    }

    public static void pushNode(String nodeId, String nodeName) {
        NODE_STACK.get().push(nodeId);
        NODE_NAMES.get().push(nodeName);
    }

    public static void popNode() {
        Deque<String> ids = NODE_STACK.get();
        if (!ids.isEmpty()) ids.pop();
        Deque<String> names = NODE_NAMES.get();
        if (!names.isEmpty()) names.pop();
    }

    /**
     * @return 当前正在执行的父节点名称（栈顶），无父节点时返回 null
     */
    public static String getParentNodeName() {
        Deque<String> names = NODE_NAMES.get();
        if (names == null || names.isEmpty()) return null;
        return names.peek();
    }

    // ==================== 栈快照（用于异步上下文恢复） ====================

    public static Deque<String> getNodeStackSnapshot() {
        return new ArrayDeque<>(NODE_STACK.get());
    }

    public static void restoreNodeStack(Deque<String> snapshot) {
        NODE_STACK.remove();
        if (snapshot != null) {
            NODE_STACK.set(snapshot);
        }
    }

    public static Deque<String> getNodeNamesSnapshot() {
        return new ArrayDeque<>(NODE_NAMES.get());
    }

    public static void restoreNodeNames(Deque<String> snapshot) {
        NODE_NAMES.remove();
        if (snapshot != null) {
            NODE_NAMES.set(snapshot);
        }
    }
}
