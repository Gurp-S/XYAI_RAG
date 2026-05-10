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

/**
 * RAG 全链路追踪切面类。
 * 负责拦截由 {@link RagTraceRoot} 和 {@link RagTraceNode} 标记的方法，
 * 在方法执行前后维护链路上下文并记录执行轨迹和耗时情况。
 *
 * <p>
 * 对于响应式（Flux/Mono）返回值，上下文清理和节点记录延迟到流终止时执行，
 * 避免在方法返回后、流订阅前过早清除 ThreadLocal。
 * </p>
 */
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
        String methodName = joinPoint.getSignature().toShortString();
        log.info("{}链路开始",methodName);
        // 2. 记录链路开始信息（存入数据库）
        traceRecordService.startRun(traceId, traceRoot.name());
        // 3. 将traceId存入上下文（ThreadLocal，保证线程安全）
        RagTraceContext.setTraceId(traceId);
        // 4. 执行目标方法（业务逻辑）
        Object result = joinPoint.proceed();
        // 5. 处理响应式返回值（Flux/Mono）：延迟清理 traceId 到流终止时
        if (result instanceof Publisher<?> publisher) {
            return wrapReactiveResult(publisher, traceId, traceRoot.name());
        }
        // 6. 同步返回值：正常清理
        RagTraceContext.clear();
        return result;
    }

    /**
     * 包装响应式结果（Flux/Mono）：在流终止/出错时记录状态并清理 traceId。
     * 避免同步 finally 提前清除 ThreadLocal 导致后续节点获取不到 traceId。
     */
    private Object wrapReactiveResult(Publisher<?> publisher, String traceId, String taskName) {
        if (publisher instanceof Flux<?> flux) {
            return flux
                    .doOnNext(v -> {
                        // 首次订阅时确保 traceId 仍然可用
                        if (RagTraceContext.getTraceId() == null) {
                            log.warn("[TRACE_ROOT] ⚠ Flux订阅后发现traceId已丢失! 重新设置 traceId={}", traceId);
                            RagTraceContext.setTraceId(traceId);
                        }
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
            return mono
                    .doOnSuccess(v -> {
                        if (RagTraceContext.getTraceId() == null) {
                            log.warn("[TRACE_ROOT] ⚠ Mono订阅后发现traceId已丢失! 重新设置 traceId={}", traceId);
                            RagTraceContext.setTraceId(traceId);
                        }
                    })
                    .doOnError(e -> {
                        log.error("[TRACE_ROOT] Mono异常, traceId={}, error={}", traceId, e.getMessage());
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
        // 1. 从上下文获取当前traceId（若没有则不追踪，避免空指针）
        String traceId = RagTraceContext.getTraceId();
        String methodName = joinPoint.getSignature().toShortString();
        log.info("{}节点开始",methodName);
        if (traceId == null || traceId.trim().isEmpty()) {
            log.warn("[TRACE_NODE] ⚠ traceId为空, 跳过追踪! method={}, nodeName='{}', thread={}",
                    methodName, traceNode.name(), Thread.currentThread().getName());
            log.warn("[TRACE_NODE]   可能原因: 1) aroundRoot的finally提前清除了traceId; " +
                    "2) 异步线程未传递ThreadLocal; 3) 该方法不在RagTraceRoot链路中");
            return joinPoint.proceed();
        }
        // 2. 生成节点唯一nodeId
        String nodeId = IdUtil.getSnowflakeNextIdStr();
        // 3. 节点入栈（维护节点层级关系，支持嵌套调用）
        RagTraceContext.pushNode(nodeId);
        try {
            // 4. 记录节点开始时间
            long startTime = System.currentTimeMillis();
            // 5. 执行目标方法
            Object result = joinPoint.proceed();
            // 6. 计算耗时，记录节点信息（存入数据库）
            long costTime = System.currentTimeMillis() - startTime;
            traceRecordService.recordNode(traceId, nodeId, traceNode.name(), traceNode.type(), costTime);
            return result;
        } catch (Exception e) {
            // 7. 记录节点异常
            traceRecordService.recordNodeError(traceId, nodeId, e.getMessage());
            throw e;
        } finally {
            // 8. 节点出栈，恢复上下文
            RagTraceContext.popNode();
        }
    }
}