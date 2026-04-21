package com.XYai.myai.rag.aop;

import cn.hutool.core.util.StrUtil;
import com.XYai.myai.rag.milvus.MilvusReconciler;
import com.XYai.myai.rag.milvus.POJO.MilvusProperties;
import com.XYai.myai.redis.RedisKeyConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import com.XYai.myai.config.Result;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Aspect
@Component
public class MilvusDeletionMonitorAspect {

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private MilvusProperties milvusProperties;

    @Resource
    private MilvusReconciler milvusReconciler;

    // 精准拦截三个接口方法：删除集合、删除文档、重建集合
    @Pointcut("execution(* com.XYai.myai.rag..*.dropCollection(..)) || " +
            "execution(* com.XYai.myai.rag..*.dropFileChunk(..)) || " +
            "execution(* com.XYai.myai.rag..*.rebuildCollection(..))")
    public void milvusDeletePointCut() {
    }

    @Around("milvusDeletePointCut()")
    public Object around(ProceedingJoinPoint point) throws Throwable {
        // 先执行业务逻辑
        Object result = point.proceed();

        boolean shouldRecord = false;
        String methodName = null;
        try {
            MethodSignature _sig = (MethodSignature) point.getSignature();
            methodName = _sig.getMethod().getName();
            if (result instanceof Result) {
                Result<?> res = (Result<?>) result;
                shouldRecord = res.getCode() != null && res.getCode() == 200;
            }
        } catch (Exception e) {
            log.warn("判断方法返回结果时异常", e);
        }

        if (shouldRecord) {
            // 异步执行统计，不阻塞接口
            CompletableFuture.runAsync(() -> {
                try {
                    doStatistic(point);
                } catch (Exception e) {
                    log.error("删除监控统计异常", e);
                }
            });
        } else {
            // 未成功的删除不记录（避免误报）
            log.debug("删除未执行或未成功，跳过监控记录，方法={}, result={}", methodName, result);
        }

        return result;
    }

    private void doStatistic(ProceedingJoinPoint point) {
        // 方法名和参数
        MethodSignature signature = (MethodSignature) point.getSignature();
        Method method = signature.getMethod();
        String methodName = method.getName();
        Object[] args = point.getArgs();

        // 阈值配置
        int globalThreshold = milvusProperties.getDeleteCountThreshold();
        int fileThreshold = milvusProperties.getDeleteCountFileThreshold();
        int collThreshold = milvusProperties.getDeleteCountCollectionThreshold();

        // 识别类型
        String type = identifyType(methodName);
        // 文件 = fileId:chunkId  集合 = collectionName
        String deleteKey = extractDeleteKey(args, methodName);
        if (!StringUtils.hasText(deleteKey)) {
            log.warn("参数为空，方法:{}", methodName);
            return;
        }

        // 写入Redis Set
        String redisKey = RedisKeyConfig.deletionMonitorByType(type);
        RSet<String> deleteSet = redissonClient.getSet(redisKey);
        // 计数
        RAtomicLong monitor = redissonClient.getAtomicLong(RedisKeyConfig.deletionMonitor());
        long monitorCount = monitor.incrementAndGet();
        deleteSet.add(deleteKey);
        int currentCount = deleteSet.size();

        // 阈值判断
        if (monitorCount > globalThreshold) {
            log.info("全局删除阈值触发，类型:{},当前数量:{}", type, monitorCount);
            resetAndReconcile(redisKey, "total", monitor);
        } else if ("fileChunk".equals(type) && currentCount > fileThreshold) {
            log.info("文件删除阈值触发，当前数量:{}", currentCount);
            resetAndReconcile(redisKey, type, monitor);
        } else if ("collection".equals(type) && currentCount > collThreshold) {
            log.info("集合删除阈值触发，当前数量:{}", currentCount);
            resetAndReconcile(redisKey, type, monitor);
        }
    }

    /**
     * 精准区分方法类型
     */
    private String identifyType(String methodName) {
        if ("dropFileChunk".equals(methodName)) {
            return "fileChunk";
        }
        if ("dropCollection".equals(methodName) || "rebuildCollection".equals(methodName)) {
            return "collection";
        }
        return "unknown";
    }

    /**
     * 核心：
     * 集合操作：返回 collectionName
     * 文件删除：返回 fileId:chunkId
     */
    private String extractDeleteKey(Object[] args, String methodName) {
        if (Objects.isNull(args) || args.length == 0) {
            return null;
        }
        try {
            switch (methodName) {
                case "dropCollection":
                case "rebuildCollection":
                    // 第0个参数：collectionName
                    return String.valueOf(args[0]);
                case "dropFileChunk":
                    // args[0] = chunkId  args[1] = fileId
                    Long chunkId = (Long) args[0];
                    String fileId = (String) args[1];
                    if (Objects.isNull(chunkId) || !StringUtils.hasText(fileId)) {
                        return null;
                    }
                    // 拼接
                    return fileId + ":" + chunkId;
                default:
                    return null;
            }
        } catch (Exception e) {
            log.error("删除标识拼接失败", e);
            return null;
        }
    }

    /**
     * 清空计数 + 清理
     */
    private void resetAndReconcile(String setKey, String reconcileType, RAtomicLong monitor) {
        RSet<String> deleteSet = redissonClient.getSet(setKey);
        try {
            // 清空计数逻辑
            if (StrUtil.equals(reconcileType, "total")) {
                monitor.set(0);
            } else {
                // 计数器 -> 减去当前删除数量
                long deleteSize = deleteSet.size();
                if (deleteSize > 0) {
                    monitor.addAndGet(-deleteSize);
                }
            }
        } catch (Exception e) {
            log.warn("删除监控集合清空失败", e);
        }
        // 清理
        CompletableFuture.runAsync(() -> {
            try {
                milvusReconciler.reconciler(reconcileType);
                log.info("Milvus 数据修复完成，类型：{}", reconcileType);
            } catch (Exception e) {
                log.error("Milvus 数据修复执行异常", e);
            }
        });
    }
}