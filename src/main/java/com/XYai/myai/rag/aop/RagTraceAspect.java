package com.XYai.myai.rag.aop;

import cn.hutool.core.util.IdUtil;
import com.XYai.myai.monitorEndpoint.service.TraceRecordService;
import com.XYai.myai.rag.aop.annotation.RagTraceContext;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.aop.annotation.RagTraceRoot;
import com.XYai.myai.rag.kafka.event.TraceLogEvent;
import com.XYai.myai.user.LoginUserInfoManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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

    @Resource(name = "kafkaTemplate")
    private KafkaTemplate<String, Object> kafkaTemplate;

    /** trace-log 为尽力而为事件：使用轻量 producer（acks=1 + 微批），不与 ETL 强一致 producer 抢资源 */
    @Resource(name = "fastKafkaTemplate")
    private KafkaTemplate<String, Object> fastKafkaTemplate;

    // ==================== Root 切面 ====================
    @Around("@annotation(com.XYai.myai.rag.aop.annotation.RagTraceRoot)")
    public Object aroundRoot(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        RagTraceRoot traceRoot = method.getAnnotation(RagTraceRoot.class);
        if (traceRoot == null) return joinPoint.proceed();
        Long userId = LoginUserInfoManager.getUserId();
        String traceId = IdUtil.getSnowflakeNextIdStr();
        RagTraceContext.setTraceId(traceId);
        RagTraceContext.setRootName(traceRoot.name());

        fastKafkaTemplate.send("trace-log", null, new TraceLogEvent(
                UUID.randomUUID().toString(), traceId, null, traceRoot.name(),
                "start", null, null, System.currentTimeMillis(), userId
        ));

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
                        fastKafkaTemplate.send("trace-log", null, new TraceLogEvent(
                                UUID.randomUUID().toString(), traceId, null, traceRoot.name(),
                                "error", ex.getMessage(), null, System.currentTimeMillis(), userId
                        ));
                    } else {
                        String finalWarn = runWarn != null ? runWarn : RagTraceContext.getAndClearRunWarn();
                        long costMs = costTime / 1_000_000;
                        if (finalWarn != null) {
                            fastKafkaTemplate.send("trace-log", null, new TraceLogEvent(
                                    UUID.randomUUID().toString(), traceId, null, traceRoot.name(),
                                    "warn", finalWarn, costMs, System.currentTimeMillis(), userId
                            ));
                        } else {
                            fastKafkaTemplate.send("trace-log", null, new TraceLogEvent(
                                    UUID.randomUUID().toString(), traceId, null, traceRoot.name(),
                                    "finish", null, costMs, System.currentTimeMillis(), userId
                            ));
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
            fastKafkaTemplate.send("trace-log", null, new TraceLogEvent(
                    UUID.randomUUID().toString(), traceId, null, traceRoot.name(),
                    "warn", runWarn, costTime / 1_000_000, System.currentTimeMillis(), userId
            ));
        } else {
            fastKafkaTemplate.send("trace-log", null, new TraceLogEvent(
                    UUID.randomUUID().toString(), traceId, null, traceRoot.name(),
                    "finish", null, costTime / 1_000_000, System.currentTimeMillis(), userId
            ));
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
        Long userId = LoginUserInfoManager.getUserId();

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
        fastKafkaTemplate.send("trace-log", null, new TraceLogEvent(
                UUID.randomUUID().toString(), traceId, nodeId, displayName,
                "node", traceNode.type(), null, System.currentTimeMillis(), userId
        ));

        long startTimeMs = System.nanoTime();
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Exception e) {
            fastKafkaTemplate.send("trace-log", null, new TraceLogEvent(
                    UUID.randomUUID().toString(), traceId, nodeId, displayName,
                    "node_error", e.getMessage(), null, System.currentTimeMillis(), userId
            ));
            RagTraceContext.popNode();
            throw e;
        }

        // 异步包装（传递快照和当前上下文快照）
        if (result instanceof CompletableFuture<?> future) {
            String nodeWarn = RagTraceContext.getAndClearNodeWarn();
            String currentPhase = RagTraceContext.getPhase();
            RagTraceContext.popNode();
            return wrapCompletableFutureNode(future, traceId, nodeId, displayName, traceNode.type(),
                    startTimeMs, stackSnapshot, namesSnapshot, rootName, currentPhase, nodeWarn, userId);
        }
        if (result instanceof Mono<?> mono) {
            String nodeWarn = RagTraceContext.getAndClearNodeWarn();
            String currentPhase = RagTraceContext.getPhase();
            RagTraceContext.popNode();
            return wrapMonoNode(mono, traceId, nodeId, displayName, traceNode.type(),
                    startTimeMs, stackSnapshot, namesSnapshot, rootName, currentPhase, nodeWarn, userId);
        }
        if (result instanceof Flux<?> flux) {
            String nodeWarn = RagTraceContext.getAndClearNodeWarn();
            String currentPhase = RagTraceContext.getPhase();
            RagTraceContext.popNode();
            return wrapFluxNode(flux, traceId, nodeId, displayName, traceNode.type(),
                    startTimeMs, stackSnapshot, namesSnapshot, rootName, currentPhase, nodeWarn, userId);
        }

        // 同步完成
        long cost = System.nanoTime() - startTimeMs;
        String warnMsg = RagTraceContext.getAndClearNodeWarn();
        if (warnMsg != null) {
            fastKafkaTemplate.send("trace-log", null, new TraceLogEvent(
                    UUID.randomUUID().toString(), traceId, nodeId, displayName,
                    "node_warn", warnMsg, cost / 1_000_000, System.currentTimeMillis(), userId
            ));
        } else {
            fastKafkaTemplate.send("trace-log", null, new TraceLogEvent(
                    UUID.randomUUID().toString(), traceId, nodeId, displayName,
                    "node_success", null, cost / 1_000_000, System.currentTimeMillis(), userId
            ));
        }
        RagTraceContext.popNode();
        return result;
    }

    // ------------------- 异步包装方法 -------------------
    private CompletableFuture<?> wrapCompletableFutureNode(CompletableFuture<?> future,
                                                           String traceId, String nodeId, String nodeName, String nodeType,
                                                           long startTimeMs, Deque<String> stackSnapshot, Deque<String> namesSnapshot,
                                                           String rootName, String phase, String nodeWarn, Long userId) {
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
                    fastKafkaTemplate.send("trace-log", null, new TraceLogEvent(
                            UUID.randomUUID().toString(), traceId, nodeId, nodeName,
                            "node_error", ex.getMessage(), cost / 1_000_000, System.currentTimeMillis(), userId
                    ));
                } else {
                    String finalWarn = nodeWarn != null ? nodeWarn : RagTraceContext.getAndClearNodeWarn();
                    if (finalWarn != null) {
                        fastKafkaTemplate.send("trace-log", null, new TraceLogEvent(
                                UUID.randomUUID().toString(), traceId, nodeId, nodeName,
                                "node_warn", finalWarn, cost / 1_000_000, System.currentTimeMillis(), userId
                        ));
                    } else {
                        fastKafkaTemplate.send("trace-log", null, new TraceLogEvent(
                                UUID.randomUUID().toString(), traceId, nodeId, nodeName,
                                "node_success", null, cost / 1_000_000, System.currentTimeMillis(), userId
                        ));
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
                                 String rootName, String phase, String nodeWarn, Long userId) {
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
                    fastKafkaTemplate.send("trace-log", null, new TraceLogEvent(
                            UUID.randomUUID().toString(), traceId, nodeId, nodeName,
                            "node_error", "Mono error", cost / 1_000_000, System.currentTimeMillis(), userId
                    ));
                } else {
                    String finalWarn = nodeWarn != null ? nodeWarn : RagTraceContext.getAndClearNodeWarn();
                    if (finalWarn != null) {
                        fastKafkaTemplate.send("trace-log", null, new TraceLogEvent(
                                UUID.randomUUID().toString(), traceId, nodeId, nodeName,
                                "node_warn", finalWarn, cost / 1_000_000, System.currentTimeMillis(), userId
                        ));
                    } else {
                        fastKafkaTemplate.send("trace-log", null, new TraceLogEvent(
                                UUID.randomUUID().toString(), traceId, nodeId, nodeName,
                                "node_success", null, cost / 1_000_000, System.currentTimeMillis(), userId
                        ));
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
                                 String rootName, String phase, String nodeWarn, Long userId) {
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
                    fastKafkaTemplate.send("trace-log", null, new TraceLogEvent(
                            UUID.randomUUID().toString(), traceId, nodeId, nodeName,
                            "node_error", "Flux error", cost / 1_000_000, System.currentTimeMillis(), userId
                    ));
                } else {
                    String finalWarn = nodeWarn != null ? nodeWarn : RagTraceContext.getAndClearNodeWarn();
                    if (finalWarn != null) {
                        fastKafkaTemplate.send("trace-log", null, new TraceLogEvent(
                                UUID.randomUUID().toString(), traceId, nodeId, nodeName,
                                "node_warn", finalWarn, cost / 1_000_000, System.currentTimeMillis(), userId
                        ));
                    } else {
                        fastKafkaTemplate.send("trace-log", null, new TraceLogEvent(
                                UUID.randomUUID().toString(), traceId, nodeId, nodeName,
                                "node_success", null, cost / 1_000_000, System.currentTimeMillis(), userId
                        ));
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