package com.XYai.myai.RAG.Aop;

import cn.hutool.core.util.IdUtil;
import com.XYai.myai.Service.TraceRecordService;
import com.XYai.myai.RAG.Aop.Annotation.RagTraceNode;
import com.XYai.myai.RAG.Aop.Annotation.RagTraceRoot;
import com.XYai.myai.RAG.RagTraceContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
// REMARK: 注意不要把注解类型和 DTO 同名（例如 RagTraceRoot 既可能是 DTO 也可能被期望为注解）。
// 如果这里的意图是拦截带注解的方法（读取注解属性），需要创建一个注解接口（@interface）并使用该注解类型，
// 而不是使用 DTO。若同时存在同名 DTO，请重命名其中之一以避免混淆。
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * RAG 全链路追踪切面类。
 * 负责拦截由 {@link RagTraceRoot} 和 {@link RagTraceNode} 标记的方法，
 * 在方法执行前后维护链路上下文并记录执行轨迹和耗时情况。
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
     * @param traceRoot 注解实例，包含任务名等信息
     * @return 目标方法执行结果
     */
    @Around("@annotation(traceRoot)")
    public Object aroundRoot(ProceedingJoinPoint joinPoint, RagTraceRoot traceRoot) throws Throwable {
        // 1. 生成全局唯一traceId（雪花算法）
        String traceId = IdUtil.getSnowflakeNextIdStr();
        // 2. 记录链路开始信息（存入数据库）
        traceRecordService.startRun(traceId, traceRoot.name());
        // 3. 将traceId存入上下文（ThreadLocal，保证线程安全）
        RagTraceContext.setTraceId(traceId);
        try {
            // 4. 执行目标方法（业务逻辑）
            return joinPoint.proceed();
        } catch (Exception e) {
            // 5. 记录异常状态
            traceRecordService.recordError(traceId, e.getMessage());
            throw e;
        } finally {
            // 6. 清理上下文，避免内存泄漏
            RagTraceContext.clear();
        }
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
        if (traceId == null || traceId.trim().isEmpty()) {
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