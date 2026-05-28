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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * RAG 链路追踪切面 – 最终稳定版（兼容虚拟线程 + 异步响应式）。
 * <p>
 * 即使底层线程池已替换为虚拟线程，跨线程时 ThreadLocal 仍不会自动继承，
 * 因此手动恢复上下文是必要的。当前实现通过闭包传递关键状态，健壮性最佳。
 * </p>
 */
@Slf4j
@Aspect
@Component
public class RagTraceAspect {

    @Resource
    private TraceRecordService traceRecordService;

    @Resource(name = "traceDbExecutor")
    private Executor traceDbExecutor;

    // ==================== Root 切面 ====================
    @Around("@annotation(com.XYai.myai.rag.aop.annotation.RagTraceRoot)")
    public Object aroundRoot(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        RagTraceRoot traceRoot = method.getAnnotation(RagTraceRoot.class);
        if (traceRoot == null) return joinPoint.proceed();

        String traceId = IdUtil.getSnowflakeNextIdStr();
        RagTraceContext.setTraceId(traceId);
        RagTraceContext.setRootName(traceRoot.name());
        traceDbExecutor.execute(() -> traceRecordService.startRun(traceId, traceRoot.name()));

        long startTime = System.nanoTime();
        Object result = joinPoint.proceed();

        // CompletableFuture 返回
        if (result instanceof CompletableFuture<?> future) {
            String runWarn = RagTraceContext.getAndClearRunWarn();
            String rootName = traceRoot.name();
            return future.whenComplete((res, ex) -> {
                // 回调线程可能为任意虚拟线程，必须手动恢复
                RagTraceContext.setTraceId(traceId);
                RagTraceContext.setRootName(rootName);
                long costTime = System.nanoTime() - startTime;
                try {
                    if (ex != null) {
                        log.error("[TRACE_ROOT] CompletableFuture异常, traceId={}", traceId, ex);
                        traceDbExecutor.execute(() -> traceRecordService.recordError(traceId, ex.getMessage()));
                    } else {
                        String finalWarn = runWarn != null ? runWarn : RagTraceContext.getAndClearRunWarn();
                        if (finalWarn != null) {
                            traceDbExecutor.execute(() -> traceRecordService.recordRunWarn(traceId, finalWarn, costTime / 1_000_000));
                        } else {
                            traceDbExecutor.execute(() -> traceRecordService.finishRun(traceId, costTime / 1_000_000));
                        }
                    }
                } catch (Exception e) {
                    log.error("[TRACE_ROOT] 更新记录失败, traceId={}", traceId, e);
                } finally {
                    RagTraceContext.clear();
                }
            });
        }

        // 同步返回
        long costTime = System.nanoTime() - startTime;
        String runWarn = RagTraceContext.getAndClearRunWarn();
        if (runWarn != null) {
            traceDbExecutor.execute(() -> traceRecordService.recordRunWarn(traceId, runWarn, costTime / 1_000_000));
        } else {
            traceDbExecutor.execute(() -> traceRecordService.finishRun(traceId, costTime / 1_000_000));
        }
        RagTraceContext.clear();
        return result;
    }

    // ==================== Node 切面 ====================
    @Around("@annotation(traceNode)")
    public Object aroundNode(ProceedingJoinPoint joinPoint, RagTraceNode traceNode) throws Throwable {
        String traceId = RagTraceContext.getTraceId();
        if (traceId == null || traceId.isBlank()) return joinPoint.proceed();

        String parentName = RagTraceContext.getParentNodeName();
        String rootName = RagTraceContext.getRootName();
        String phase = RagTraceContext.getPhase();
        String rawName = traceNode.name();

        // 显示名称优先级：父节点 > 阶段标签 > 根名称
        String displayName;
        if (parentName != null) {
            displayName = rawName + "(" + parentName + ")";
        } else if (phase != null) {
            displayName = rawName + "(" + phase + ")";
        } else if (rootName != null) {
            displayName = rawName + "(" + rootName + ")";
        } else {
            displayName = rawName;
        }

        String nodeId = IdUtil.getSnowflakeNextIdStr();
        Deque<String> stackSnapshot = RagTraceContext.getNodeStackSnapshot();
        Deque<String> namesSnapshot = RagTraceContext.getNodeNamesSnapshot();
        RagTraceContext.pushNode(nodeId, displayName);
        traceDbExecutor.execute(() -> traceRecordService.recordNode(traceId, nodeId, displayName, traceNode.type()));

        long startTimeMs = System.nanoTime();
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Exception e) {
            traceDbExecutor.execute(() -> traceRecordService.recordNodeError(traceId, nodeId, e.getMessage()));
            RagTraceContext.popNode();
            throw e;
        }

        // 异步包装（传递快照和当前上下文快照）
        if (result instanceof CompletableFuture<?> future) {
            String nodeWarn = RagTraceContext.getAndClearNodeWarn();
            String currentPhase = RagTraceContext.getPhase();
            RagTraceContext.popNode();
            return wrapCompletableFutureNode(future, traceId, nodeId, displayName, traceNode.type(),
                    startTimeMs, stackSnapshot, namesSnapshot, rootName, currentPhase, nodeWarn);
        }
        if (result instanceof Mono<?> mono) {
            String nodeWarn = RagTraceContext.getAndClearNodeWarn();
            String currentPhase = RagTraceContext.getPhase();
            RagTraceContext.popNode();
            return wrapMonoNode(mono, traceId, nodeId, displayName, traceNode.type(),
                    startTimeMs, stackSnapshot, namesSnapshot, rootName, currentPhase, nodeWarn);
        }
        if (result instanceof Flux<?> flux) {
            String nodeWarn = RagTraceContext.getAndClearNodeWarn();
            String currentPhase = RagTraceContext.getPhase();
            RagTraceContext.popNode();
            return wrapFluxNode(flux, traceId, nodeId, displayName, traceNode.type(),
                    startTimeMs, stackSnapshot, namesSnapshot, rootName, currentPhase, nodeWarn);
        }

        // 同步完成
        long cost = System.nanoTime() - startTimeMs;
        String warnMsg = RagTraceContext.getAndClearNodeWarn();
        if (warnMsg != null) {
            traceDbExecutor.execute(() -> traceRecordService.recordNodeWarn(traceId, nodeId, warnMsg, cost / 1_000_000));
        } else {
            traceDbExecutor.execute(() -> traceRecordService.updateNode(traceId, nodeId, displayName, traceNode.type(), cost / 1_000_000));
        }
        RagTraceContext.popNode();
        return result;
    }

    // ------------------- 异步包装方法 -------------------
    private CompletableFuture<?> wrapCompletableFutureNode(CompletableFuture<?> future,
                                                           String traceId, String nodeId, String nodeName, String nodeType,
                                                           long startTimeMs, Deque<String> stackSnapshot, Deque<String> namesSnapshot,
                                                           String rootName, String phase, String nodeWarn) {
        return future.whenComplete((res, ex) -> {
            long cost = System.nanoTime() - startTimeMs;
            // 手动恢复所有上下文（虚拟线程下依然必要）
            RagTraceContext.setTraceId(traceId);
            RagTraceContext.setRootName(rootName);
            if (phase != null) RagTraceContext.setPhase(phase);
            RagTraceContext.restoreNodeStack(stackSnapshot);
            RagTraceContext.restoreNodeNames(namesSnapshot);
            RagTraceContext.pushNode(nodeId, nodeName);
            try {
                if (ex != null) {
                    traceDbExecutor.execute(() -> traceRecordService.recordNodeError(traceId, nodeId, ex.getMessage()));
                } else {
                    String finalWarn = nodeWarn != null ? nodeWarn : RagTraceContext.getAndClearNodeWarn();
                    if (finalWarn != null) {
                        traceDbExecutor.execute(() -> traceRecordService.recordNodeWarn(traceId, nodeId, finalWarn, cost / 1_000_000));
                    } else {
                        traceDbExecutor.execute(() -> traceRecordService.updateNode(traceId, nodeId, nodeName, nodeType, cost / 1_000_000));
                    }
                }
            } catch (Exception e) {
                log.error("[TRACE_NODE] 更新节点记录失败, traceId={}, nodeId={}", traceId, nodeId, e);
            } finally {
                RagTraceContext.popNode();
            }
        });
    }

    private Mono<?> wrapMonoNode(Mono<?> mono, String traceId, String nodeId, String nodeName, String nodeType,
                                 long startTimeMs, Deque<String> stackSnapshot, Deque<String> namesSnapshot,
                                 String rootName, String phase, String nodeWarn) {
        return mono.doFinally(signal -> {
            long cost = System.nanoTime() - startTimeMs;
            RagTraceContext.setTraceId(traceId);
            RagTraceContext.setRootName(rootName);
            if (phase != null) RagTraceContext.setPhase(phase);
            RagTraceContext.restoreNodeStack(stackSnapshot);
            RagTraceContext.restoreNodeNames(namesSnapshot);
            RagTraceContext.pushNode(nodeId, nodeName);
            try {
                if (signal == reactor.core.publisher.SignalType.ON_ERROR) {
                    traceDbExecutor.execute(() -> traceRecordService.recordNodeError(traceId, nodeId, "Mono error"));
                } else {
                    String finalWarn = nodeWarn != null ? nodeWarn : RagTraceContext.getAndClearNodeWarn();
                    if (finalWarn != null) {
                        traceDbExecutor.execute(() -> traceRecordService.recordNodeWarn(traceId, nodeId, finalWarn, cost / 1_000_000));
                    } else {
                        traceDbExecutor.execute(() -> traceRecordService.updateNode(traceId, nodeId, nodeName, nodeType, cost / 1_000_000));
                    }
                }
            } catch (Exception e) {
                log.error("[TRACE_NODE] 更新节点记录失败, traceId={}, nodeId={}", traceId, nodeId, e);
            } finally {
                RagTraceContext.popNode();
            }
        });
    }

    private Flux<?> wrapFluxNode(Flux<?> flux, String traceId, String nodeId, String nodeName, String nodeType,
                                 long startTimeMs, Deque<String> stackSnapshot, Deque<String> namesSnapshot,
                                 String rootName, String phase, String nodeWarn) {
        return flux.doFinally(signal -> {
            long cost = System.nanoTime() - startTimeMs;
            RagTraceContext.setTraceId(traceId);
            RagTraceContext.setRootName(rootName);
            if (phase != null) RagTraceContext.setPhase(phase);
            RagTraceContext.restoreNodeStack(stackSnapshot);
            RagTraceContext.restoreNodeNames(namesSnapshot);
            RagTraceContext.pushNode(nodeId, nodeName);
            try {
                if (signal == reactor.core.publisher.SignalType.ON_ERROR) {
                    traceDbExecutor.execute(() -> traceRecordService.recordNodeError(traceId, nodeId, "Flux error"));
                } else {
                    String finalWarn = nodeWarn != null ? nodeWarn : RagTraceContext.getAndClearNodeWarn();
                    if (finalWarn != null) {
                        traceDbExecutor.execute(() -> traceRecordService.recordNodeWarn(traceId, nodeId, finalWarn, cost / 1_000_000));
                    } else {
                        traceDbExecutor.execute(() -> traceRecordService.updateNode(traceId, nodeId, nodeName, nodeType, cost / 1_000_000));
                    }
                }
            } catch (Exception e) {
                log.error("[TRACE_NODE] 更新节点记录失败, traceId={}, nodeId={}", traceId, nodeId, e);
            } finally {
                RagTraceContext.popNode();
            }
        });
    }
}