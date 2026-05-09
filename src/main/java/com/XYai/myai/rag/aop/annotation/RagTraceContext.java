package com.XYai.myai.rag.aop.annotation;

import com.alibaba.ttl.TransmittableThreadLocal;

import java.util.Deque;
import java.util.LinkedList;

/**
 * RAG 链路追踪上下文类。
 * 使用 TransmittableThreadLocal 维护 traceId 和节点调用栈，
 * 确保在异步线程池和响应式编程环境下链路信息的正确传递。
 */
public class RagTraceContext {

    // 使用 TransmittableThreadLocal 确保跨线程传递
    private static final ThreadLocal<String> TRACE_ID_HOLDER = new TransmittableThreadLocal<>();
    private static final ThreadLocal<Deque<String>> NODE_STACK = new TransmittableThreadLocal<>();

    public static void clear() {
        // 清除由当前线程保存的 TRACE_ID_HOLDER 和 NODE_STACK 的内容，调用 remove() 防止内存泄露
        TRACE_ID_HOLDER.remove();
        NODE_STACK.remove();
    }

    public static String getTraceId() {
        // 从 TRACE_ID_HOLDER 中获取当前的 traceId 并返回
        return TRACE_ID_HOLDER.get();
    }

    public static void setTraceId(String traceId) {
        // 将 traceId 放到 TRACE_ID_HOLDER 中
        TRACE_ID_HOLDER.set(traceId);
    }

    public static void pushNode(String nodeId) {
        Deque<String> nodes = NODE_STACK.get();
        if (nodes == null) {
            nodes = new LinkedList<>();
        }
        nodes.push(nodeId);
        NODE_STACK.set(nodes);
    }

    public static void popNode() {
        Deque<String> nodes = NODE_STACK.get();
        if (nodes != null && !nodes.isEmpty()) {
            nodes.pop();
            NODE_STACK.set(nodes);
        }
    }

    public static String getCurrentNodeId() {
        Deque<String> nodes = NODE_STACK.get();
        if (nodes != null && !nodes.isEmpty()) {
            return nodes.peek();
        }
        return null;
    }
}
