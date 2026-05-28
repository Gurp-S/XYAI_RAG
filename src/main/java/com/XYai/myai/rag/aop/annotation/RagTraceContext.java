package com.XYai.myai.rag.aop.annotation;

import com.alibaba.ttl.TransmittableThreadLocal;
import java.util.ArrayDeque;
import java.util.Deque;

public class RagTraceContext {

    private static final ThreadLocal<String> TRACE_ID_HOLDER = new TransmittableThreadLocal<>();
    private static final ThreadLocal<Deque<String>> NODE_STACK = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Deque<String>> NODE_NAMES = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<String> NODE_WARN_HOLDER = new TransmittableThreadLocal<>();
    private static final ThreadLocal<String> RUN_WARN_HOLDER = new TransmittableThreadLocal<>();
    private static final ThreadLocal<String> ROOT_NAME_HOLDER = new TransmittableThreadLocal<>();
    // 阶段标签，用于替代根名称作为顶层节点的“来源”
    private static final ThreadLocal<String> PHASE_HOLDER = new TransmittableThreadLocal<>();

    public static void clear() {
        TRACE_ID_HOLDER.remove();
        NODE_STACK.remove();
        NODE_NAMES.remove();
        NODE_WARN_HOLDER.remove();
        RUN_WARN_HOLDER.remove();
        ROOT_NAME_HOLDER.remove();
        PHASE_HOLDER.remove();
    }

    // ==================== TraceId ====================
    public static String getTraceId() { return TRACE_ID_HOLDER.get(); }
    public static void setTraceId(String traceId) { TRACE_ID_HOLDER.set(traceId); }

    // ==================== Root name ====================
    public static void setRootName(String rootName) { ROOT_NAME_HOLDER.set(rootName); }
    public static String getRootName() { return ROOT_NAME_HOLDER.get(); }

    // ==================== Phase ====================
    public static void setPhase(String phase) { PHASE_HOLDER.set(phase); }
    public static String getPhase() { return PHASE_HOLDER.get(); }

    // ==================== Node stack ====================
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

    public static String getParentNodeName() {
        Deque<String> names = NODE_NAMES.get();
        return (names == null || names.isEmpty()) ? null : names.peek();
    }

    public static Deque<String> getNodeStackSnapshot() {
        return new ArrayDeque<>(NODE_STACK.get());
    }

    public static void restoreNodeStack(Deque<String> snapshot) {
        NODE_STACK.remove();
        if (snapshot != null) NODE_STACK.set(snapshot);
    }

    public static Deque<String> getNodeNamesSnapshot() {
        return new ArrayDeque<>(NODE_NAMES.get());
    }

    public static void restoreNodeNames(Deque<String> snapshot) {
        NODE_NAMES.remove();
        if (snapshot != null) NODE_NAMES.set(snapshot);
    }

    // ==================== Warn messages ====================
    public static void setNodeWarn(String message) { NODE_WARN_HOLDER.set(message); }
    public static String getAndClearNodeWarn() {
        String msg = NODE_WARN_HOLDER.get();
        NODE_WARN_HOLDER.remove();
        return msg;
    }

    public static void setRunWarn(String message) { RUN_WARN_HOLDER.set(message); }
    public static String getAndClearRunWarn() {
        String msg = RUN_WARN_HOLDER.get();
        RUN_WARN_HOLDER.remove();
        return msg;
    }
}