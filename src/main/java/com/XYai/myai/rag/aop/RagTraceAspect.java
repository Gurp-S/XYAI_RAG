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
import org.reactivestreams.Publisher;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;


@Slf4j
@Aspect
@Component
public class RagTraceAspect {

    @Resource
    private TraceRecordService traceRecordService;

    /**
     * 环绕通知：在带 {@link RagTraceRoot} 注解的方法执行前后进行全链路 traceId 管理与记录。
     *
     * @param joinPoint 切入点
     * @return 目标方法执行结果
     */
    @Around("@annotation(com.XYai.myai.rag.aop.annotation.RagTraceRoot)")
    public Object aroundRoot(ProceedingJoinPoint joinPoint) throws Throwable {
        // 从 joinPoint 获取注解实例
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        RagTraceRoot traceRoot = method.getAnnotation(RagTraceRoot.class);
        if (traceRoot == null) {
            return joinPoint.proceed();
        }
        // 1. 优先复用外部已经放入上下文的 traceId/taskId
        String traceId = IdUtil.getSnowflakeNextIdStr();
        // 2. 记录链路开始信息（存入数据库）
        traceRecordService.startRun(traceId, traceRoot.name());
        // 3. 将traceId存入上下文（ThreadLocal，保证线程安全）
        RagTraceContext.setTraceId(traceId);
        // 4. 执行目标方法（业务逻辑）
        long startTime = System.currentTimeMillis();
        Object result = joinPoint.proceed();
        // 5. 处理响应式返回值（Flux/Mono）：延迟清理 traceId 到流终止时
        if (result instanceof Publisher<?> publisher) {
            return wrapReactiveResult(publisher, traceId,startTime);
        }
        // 6. 同步返回值：正常清理
        long costTime = System.currentTimeMillis() - startTime;
        traceRecordService.finishRun(traceId, costTime);
        RagTraceContext.clear();
        return result;
    }

    /**
     * 包装响应式结果（Flux/Mono）：在流终止/出错时记录状态并清理 traceId。
     * 避免同步 finally 提前清除 ThreadLocal 导致后续节点获取不到 traceId。
     */
    private Object wrapReactiveResult(Publisher<?> publisher, String traceId, long startTime) {
        if (publisher instanceof Flux<?> flux) {
            return flux
                    .doOnNext(v -> {
                        // 首次订阅时确保 traceId 仍然可用
                        if (RagTraceContext.getTraceId() == null) {
                            RagTraceContext.setTraceId(traceId);
                        }
                    })
                    .doOnComplete(() -> {
                        long costTime = System.currentTimeMillis() - startTime;
                        traceRecordService.finishRun(traceId, costTime);
                    })
                    .doOnError(e -> {
                        log.error("[TRACE_ROOT] Flux异常, traceId={}, error={}", traceId, e.getMessage());
                        traceRecordService.recordError(traceId, e.getMessage());
                    })
                    .doFinally(signal -> {
                        RagTraceContext.clear();
                    });
        }
        if (publisher instanceof Mono<?> mono) {
            log.info("[TRACE_ROOT] 包装Mono, traceId={} 将在流终止时清理", traceId);
            return mono
                    .doOnSuccess(v -> {
                        if (RagTraceContext.getTraceId() == null) {
                            RagTraceContext.setTraceId(traceId);
                        }
                        long costTime = System.currentTimeMillis() - startTime;
                        traceRecordService.finishRun(traceId, costTime);
                    })
                    .doOnError(e -> {
                        traceRecordService.recordError(traceId, e.getMessage());
                    })
                    .doFinally(signal -> {
                        RagTraceContext.clear();
                    });
        }
        // 其他 Publisher 类型，保守处理
        log.warn("[TRACE_ROOT] 未知的Publisher类型: {}, 不做包装处理", publisher.getClass().getName());
        return publisher;
    }

    /**
     * 环绕通知：为带 {@link RagTraceNode} 注解的方法创建节点记录（nodeId），并在方法完成后记录耗时与状态。
     *
     * @param joinPoint 切入点
     * @param traceNode 注解实例，包含节点名称与类型
     * @return 目标方法执行结果
     */
    @Around("@annotation(traceNode)")
    public Object aroundNode(ProceedingJoinPoint joinPoint, RagTraceNode traceNode) throws Throwable {
        String traceId = RagTraceContext.getTraceId();
        if (traceId == null) return joinPoint.proceed();

        String nodeId = IdUtil.getSnowflakeNextIdStr();
        String nodeName = traceNode.name();
        String nodeType = traceNode.type();

        traceRecordService.recordNode(traceId, nodeId, nodeName, nodeType);
        Deque<String> stackSnapshot = RagTraceContext.getNodeStackSnapshot();
        RagTraceContext.pushNode(nodeId);

        Object result = null;
        try {
            long startTimeMs = System.currentTimeMillis();
            result = joinPoint.proceed();
            if (result instanceof CompletableFuture<?> future) {
                // 异步节点：弹出刚才入栈的当前节点（因为回调中会重新入栈）
                RagTraceContext.popNode();
                return wrapCompletableFuture(future, traceId, nodeId, nodeName, nodeType, startTimeMs, stackSnapshot);
            }
            long cost = System.currentTimeMillis() - startTimeMs;
            traceRecordService.updateNode(traceId, nodeId, nodeName, nodeType, cost);
            return result;
        } catch (Exception e) {
            traceRecordService.recordNodeError(traceId, nodeId, e.getMessage());
            throw e;
        } finally {
            if (!(result instanceof CompletableFuture)) {
                RagTraceContext.popNode();
            }
        }
    }

    private CompletableFuture<?> wrapCompletableFuture(CompletableFuture<?> future,
                                                       String traceId,
                                                       String nodeId,
                                                       String nodeName,
                                                       String nodeType,
                                                       long startTimeMs,
                                                       Deque<String> stackSnapshot) {
        return future.whenComplete((result, ex) -> {
            long cost = System.currentTimeMillis() - startTimeMs;
            RagTraceContext.setTraceId(traceId);
            RagTraceContext.restoreNodeStack(stackSnapshot);
            RagTraceContext.pushNode(nodeId);
            try {
                if (ex != null) {
                    traceRecordService.recordNodeError(traceId, nodeId, ex.getMessage());
                } else {
                    traceRecordService.updateNode(traceId, nodeId, nodeName, nodeType, cost);
                }
            } finally {
                RagTraceContext.popNode();
            }
        });
    }
}