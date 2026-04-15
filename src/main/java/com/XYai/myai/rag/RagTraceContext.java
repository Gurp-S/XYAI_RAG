package com.XYai.myai.rag;

import java.util.Deque;
import java.util.LinkedList;

/**
 * RAG 链路追踪上下文类。
 * 利用 ThreadLocal 维护 traceId 和节点调用栈，确保在多线程环境下链路信息的隔离与准确记录。
 */
public class RagTraceContext {

    // 定义存储 TraceId 的 ThreadLocal
    private static final ThreadLocal<String> TRACE_ID_HOLDER = new ThreadLocal<>();
    // 定义存储 NodeId 栈的 ThreadLocal，用于表示节点的层级关系。
    // 使用 Deque 或 LinkedList：
    private static final ThreadLocal<Deque<String>> NODE_STACK = ThreadLocal.withInitial(LinkedList::new);

    public static void setTraceId(String traceId) {
        // 将 traceId 放到 TRACE_ID_HOLDER 中
        TRACE_ID_HOLDER.set(traceId);
    }

    public static void clear() {
        // 清除由当前线程保存的 TRACE_ID_HOLDER 和 NODE_STACK 的内容，调用 remove() 防止内存泄露
        TRACE_ID_HOLDER.remove();
        NODE_STACK.remove();
    }

    public static String getTraceId() {
        // 从 TRACE_ID_HOLDER 中获取当前的 traceId 并返回
        return TRACE_ID_HOLDER.get();
    }

    public static void pushNode(String nodeId) {
        // 获取当前线程的 NODE_STACK 实例，并将 nodeId 压入栈（如 push(nodeId)）
        Deque<String> nodes = NODE_STACK.get();
        nodes.push(nodeId);
        NODE_STACK.set(nodes);
    }

    public static void popNode() {
        // 获取当前线程的 NODE_STACK 实例。如果非空，把栈顶的 nodeId 弹出（如 pop()）；注意判断空栈避免抛出异常
        Deque<String> nodes = NODE_STACK.get();
        if (nodes != null && !nodes.isEmpty()) {
            nodes.pop();
            NODE_STACK.set(nodes);
        } else {
            throw new RuntimeException("无节点");
        }
    }

    // 获取当前所在节点：peek() 返回栈顶节点，便于记录关联
    public static String getCurrentNodeId() {
        Deque<String> nodes = NODE_STACK.get();
        if (nodes != null && !nodes.isEmpty()) {
            return nodes.peek();
        }
        return null;
    }
}
