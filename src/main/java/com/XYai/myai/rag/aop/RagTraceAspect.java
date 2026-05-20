package com.XYai.myai.rag.aop;

import cn.hutool.core.util.IdUtil;
import com.XYai.myai.monitorEndpoint.service.TraceRecordService;
import com.XYai.myai.rag.aop.annotation.RagTraceContext;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.aop.annotation.RagTraceRoot;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;

/**
 * RAG 链路追踪切面（非响应式版）
 * 支持同步方法和 CompletableFuture 返回值。
 */
@Slf4j
@Aspect
@Component
public class RagTraceAspect {

    @Resource
    private TraceRecordService traceRecordService;

    /**
     * 环绕通知：在带 {@link RagTraceRoot} 注解的方法执行前后进行全链路 traceId 管理与记录。
     * 支持同步返回和 CompletableFuture 返回（在 future 完成时记录结束）。
     */
    @Around("@annotation(com.XYai.myai.rag.aop.annotation.RagTraceRoot)")
    public Object aroundRoot(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        RagTraceRoot traceRoot = method.getAnnotation(RagTraceRoot.class);
        if (traceRoot == null) {
            return joinPoint.proceed();
        }

        String traceId = IdUtil.getSnowflakeNextIdStr();
        traceRecordService.startRun(traceId, traceRoot.name());
        RagTraceContext.setTraceId(traceId);

        long startTime = System.nanoTime();
        Object result = joinPoint.proceed();

        if (result instanceof CompletableFuture<?> future) {
            return future.whenComplete((res, ex) -> {
                long costTime = System.nanoTime() - startTime;
                try {
                    if (ex != null) {
                        log.error("[TRACE_ROOT] CompletableFuture异常, traceId={}, error={}", traceId, ex.getMessage());
                        traceRecordService.recordError(traceId, ex.getMessage());
                    } else {
                        String runWarn = RagTraceContext.getAndClearRunWarn();
                        if (runWarn != null) {
                            traceRecordService.recordRunWarn(traceId, runWarn, costTime / 1_000_000);
                        } else {
                            traceRecordService.finishRun(traceId, costTime / 1_000_000);
                        }
                    }
                } catch (Exception e) {
                    log.error("[TRACE_ROOT] 更新trace记录失败, traceId={}, error={}", traceId, e.getMessage(), e);
                } finally {
                    RagTraceContext.clear();
                }
            });
        }

        // 同步返回值：正常记录（优先检查 warn 消息）
        long costTime = System.nanoTime() - startTime;
        String runWarn = RagTraceContext.getAndClearRunWarn();
        if (runWarn != null) {
            traceRecordService.recordRunWarn(traceId, runWarn, costTime / 1_000_000);
        } else {
            traceRecordService.finishRun(traceId, costTime / 1_000_000);
        }
        RagTraceContext.clear();
        return result;
    }

    /**
     * 环绕通知：为带 {@link RagTraceNode} 注解的方法创建节点记录，
     * 在方法/CompletableFuture 完成后记录耗时与状态。
     * 自动拼接父节点名称生成显示名，如 "bm25打分(记忆召回)"。
     */
    @Around("@annotation(traceNode)")
    public Object aroundNode(ProceedingJoinPoint joinPoint, RagTraceNode traceNode) throws Throwable {
        String traceId = RagTraceContext.getTraceId();
        if (traceId == null || traceId.trim().isEmpty()) {
            return joinPoint.proceed();
        }

        // 拼接父节点名称，生成更具辨识度的显示名
        String parentName = RagTraceContext.getParentNodeName();
        String rawName = traceNode.name();
        String displayName = parentName != null ? rawName + "(" + parentName + ")" : rawName;

        String nodeId = IdUtil.getSnowflakeNextIdStr();
        Deque<String> stackSnapshot = RagTraceContext.getNodeStackSnapshot();
        Deque<String> namesSnapshot = RagTraceContext.getNodeNamesSnapshot();
        RagTraceContext.pushNode(nodeId, displayName);
        traceRecordService.recordNode(traceId, nodeId, displayName, traceNode.type());

        boolean isCfPath = false;
        try {
            long startTimeMs = System.nanoTime();
            Object result = joinPoint.proceed();

            if (result instanceof CompletableFuture<?> future) {
                isCfPath = true;
                RagTraceContext.popNode();
                return wrapCompletableFutureNode(future, traceId, nodeId,
                        displayName, traceNode.type(), startTimeMs, stackSnapshot, namesSnapshot);
            }

            // 同步返回：直接记录节点完成（优先检查 warn 消息）
            long cost = System.nanoTime() - startTimeMs;
            String warnMsg = RagTraceContext.getAndClearNodeWarn();
            if (warnMsg != null) {
                traceRecordService.recordNodeWarn(traceId, nodeId, warnMsg, cost / 1_000_000);
            } else {
                traceRecordService.updateNode(traceId, nodeId, displayName, traceNode.type(), cost / 1_000_000);
            }
            return result;

        } catch (Exception e) {
            traceRecordService.recordNodeError(traceId, nodeId, e.getMessage());
            throw e;
        } finally {
            if (!isCfPath) {
                RagTraceContext.popNode();
            }
        }
    }

    /**
     * 包装 CompletableFuture 节点：在 future 完成时记录节点耗时和状态。
     * 调用前当前节点已从栈中弹出（popNode），回调中重新入栈再弹出，
     * 以模拟正常调用栈生命周期，并确保在 future 线程中恢复上下文。
     */
    private CompletableFuture<?> wrapCompletableFutureNode(CompletableFuture<?> future,
                                                           String traceId, String nodeId,
                                                           String nodeName, String nodeType,
                                                           long startTimeMs,
                                                           Deque<String> stackSnapshot,
                                                           Deque<String> namesSnapshot) {
        return future.whenComplete((result, ex) -> {
            long cost = System.nanoTime() - startTimeMs;
            RagTraceContext.setTraceId(traceId);
            RagTraceContext.restoreNodeStack(stackSnapshot);
            RagTraceContext.restoreNodeNames(namesSnapshot);
            RagTraceContext.pushNode(nodeId, nodeName);
            try {
                if (ex != null) {
                    traceRecordService.recordNodeError(traceId, nodeId, ex.getMessage());
                } else {
                    String warnMsg = RagTraceContext.getAndClearNodeWarn();
                    if (warnMsg != null) {
                        traceRecordService.recordNodeWarn(traceId, nodeId, warnMsg, cost / 1_000_000);
                    } else {
                        traceRecordService.updateNode(traceId, nodeId, nodeName, nodeType, cost / 1_000_000);
                    }
                }
            } catch (Exception e) {
                log.error("[TRACE_NODE] 更新节点记录失败, traceId={}, nodeId={}, nodeName={}, error={}",
                        traceId, nodeId, nodeName, e.getMessage(), e);
                try {
                    traceRecordService.recordNodeError(traceId, nodeId, "update failed: " + e.getMessage());
                } catch (Exception ignored) {
                }
            } finally {
                RagTraceContext.popNode();
            }
        });
    }
}
