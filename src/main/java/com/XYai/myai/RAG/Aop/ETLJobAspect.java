package com.XYai.myai.RAG.Aop;

import com.XYai.myai.RAG.Aop.Annotation.TaskTracker;
import com.XYai.myai.RAG.ETLpipeline.POJO.IngestionContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.annotations.Arg;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Aspect
@Component
@Slf4j
public class ETLJobAspect {
    @Resource
    private TaskTracker taskTracker;

    // 预定义的各个节点的进度映射
    private static final Map<String, Integer> NODE_PROGRESS = Map.of(
            "fetcher", 20,
            "parser", 40,
            "enricher", 60,
            "chunker", 80,
            "indexer", 95
    );

    // 切入标记了 @TrackETLJob 的方法
    @Pointcut("@annotation(com.XYai.myai.RAG.Aop.Annotation.TrackETLJob)")
    public void etlPointcut() {}

    // 切入 Ingestion 接口实现类的 execute 方法
    @Pointcut("execution(* com.XYai.myai.RAG.ETLpipeline.Nodes..*.execute(..))")
    public void nodeExecutePointcut() {}

    @Before("etlPointcut()")
    public void beforeUpload(JoinPoint joinPoint) {
        String fileName = getFileName(joinPoint);
        if (fileName != null) {
            taskTracker.update(fileName, "STARTING", 5);
        }
    }

    @AfterReturning(pointcut = "etlPointcut()", returning = "result")
    public void afterSuccess(JoinPoint joinPoint, Object result) {
        String fileName = getFileName(joinPoint);
        if (fileName != null) {
            taskTracker.update(fileName, "COMPLETED", 100);
        }
    }

    @AfterThrowing(pointcut = "etlPointcut()", throwing = "ex")
    public void afterFailed(JoinPoint joinPoint, Exception ex) {
        String fileName = getFileName(joinPoint);
        if (fileName != null) {
            taskTracker.update(fileName, "FAILED: " + ex.getMessage(), 0);
        }
    }

    @AfterReturning(pointcut = "nodeExecutePointcut()")
    public void afterNodeExecute(JoinPoint joinPoint) {
        log.info("AOP 命中节点执行: {}", joinPoint.getSignature().toShortString());
        Object[] args = joinPoint.getArgs();
        if (args.length >= 1 && args[0] instanceof IngestionContext context) {
            if (context.getDocument() != null && context.getDocument().getMetadata() != null) {
                Object fileNameObj = context.getDocument().getMetadata().get("fileName");
                if (fileNameObj != null) {
                    String fileName = fileNameObj.toString();
                    // 获取当前执行的节点类型
                    Object target = joinPoint.getTarget();
                    if (target instanceof com.XYai.myai.RAG.ETLpipeline.Nodes.Ingestion node) {
                        String nodeType = node.getNodeType().toLowerCase();
                        Integer progress = NODE_PROGRESS.getOrDefault(nodeType, null);
                        if (progress != null) {
                            taskTracker.update(fileName, "PROCESSING: " + nodeType.toUpperCase(), progress);
                        }
                    }
                }
            }
        }
    }

    private String getFileName(JoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        for (Object arg : args) {
            if (arg instanceof IngestionContext context) {
                //  IngestionContext 里存了文件名，比如在 metadata 里
                return context.getDocument().getMetadata().get("fileName").toString();
            }
        }
        return null;
    }
}
