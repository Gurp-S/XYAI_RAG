package com.XYai.myai.rag.channel.processor;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.channel.pojo.SearchContext;
import com.XYai.myai.rag.milvus.MilvusAclManager;
import com.XYai.myai.rag.milvus.MilvusFileManager;
import com.XYai.myai.redis.RedisKeyConfig;
import com.XYai.myai.user.LoginUserInfoManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 过滤后处理器。
 *
 * <p>定位：召回后处理中间阶段（去重之后、重排之前），负责做“硬规则”剔除。</p>
 * <p>目标：把明显不合格候选提前过滤，避免污染 Rerank 输入。</p>
 */
@Slf4j
@Component
public class FilterPostProcessor implements SearchResultPostProcessor {

    private static final String NAME = "filter-processor";
    @Resource
    private RedissonClient redissonClient;
    @Autowired
    private MilvusAclManager milvusAclManager;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return 5;  // 在去重之后，Rerank之前
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks, SearchContext context) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        Long userId = LoginUserInfoManager.getUserId();
        if (userId == null) {
            log.warn("FilterPostProcessor: 未登录用户执行过滤，返回空结果");
            return List.of();
        }

        return chunks.stream()
                .filter(Objects::nonNull)
                .filter(chunk -> {
                    if (!FilterPostProcessor.filterComplete(chunk)) {
                        log.warn("[filterComplete] 丢弃 chunk: fileId={}, chunkId={}",
                                chunk.getMetadata() != null ? chunk.getMetadata().get("fileId") : null,
                                chunk.getMetadata() != null ? chunk.getMetadata().get("chunkId") : null);
                        return false;
                    }
                    return true;
                })
                .filter(chunk -> {
                    if (!filterScore(chunk)) {
                        log.warn("[filterScore] 丢弃 chunk: score={}, bm25={}, chunkId={}",
                                chunk.getScore(), chunk.getBm25Score(),
                                chunk.getMetadata().get("chunkId"));
                        return false;
                    }
                    return true;
                })
                .filter(chunk -> {
                    if (!filterPermission(chunk, userId)) {
                        log.warn("[filterPermission] 丢弃 chunk: fileId={}, chunkId={}, visibility={}",
                                chunk.getMetadata().get("fileId"),
                                chunk.getMetadata().get("chunkId"),
                                chunk.getMetadata().get("visibility"));
                        return false;
                    }
                    return true;
                })
                .filter(chunk -> {
                    if (!filterContentLength(chunk)) {
                        log.warn("[filterContentLength] 丢弃 chunk: length={}, chunkId={}",
                                chunk.getContent() != null ? chunk.getContent().length() : 0,
                                chunk.getMetadata().get("chunkId"));
                        return false;
                    }
                    return true;
                })
                .toList();
    }

    private boolean filterUnloadCollection(RetrievedChunk chunk) {
        // 加载的集合
        Long userId = LoginUserInfoManager.getUserId();
        RSet<String> loadCollections = redissonClient.getSet(RedisKeyConfig.userLoadCollectionsKey(userId));
        // 集合下fileIdChunk
        Set<Object> fileChunkIds = loadCollections.stream()
                .map(loadCollection ->
                        redissonClient.getSet(RedisKeyConfig.collectionFileIds(loadCollection)))
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
        return fileChunkIds.contains(chunk.getMetadata().get("fileId"));
    }

    private boolean filterContentLength(RetrievedChunk chunk) {
        String content = chunk.getContent();
        if (content == null) return false;
        int chunkLength = content.length();
        return chunkLength >= 5 && chunkLength <= 2000;
    }

    private boolean filterScore(RetrievedChunk chunk) {
        Double score = chunk.getScore();
        Double bm25Score = chunk.getBm25Score();
        if (score == null) return false;
        boolean commonScore = score > 0.5 && bm25Score > 0.5;
        boolean highAndLowScore = (score > 0.82 && bm25Score >0.2) ||
               (bm25Score > 0.82 && score > 0.2);
        return commonScore || highAndLowScore ;
    }

    private static Boolean filterComplete(RetrievedChunk chunk) {
        if (chunk == null) return false;
        if (chunk.getContent() == null) return false;
        Map<String, Object> meta = chunk.getMetadata();
        if (meta == null) return false;
        Object fileId = meta.get("fileId");
        Object chunkId = meta.get("chunkId");
        return fileId != null && chunkId != null;
    }

    /**
     * 权限过滤
     *
     * @param chunk
     * @param userId
     */
    private boolean filterPermission(RetrievedChunk chunk, long userId) {
        try {
            // 1. 元数据空值校验
            Map<String, Object> metadata = chunk.getMetadata();
            if (MapUtil.isEmpty(metadata)) {
                return false;
            }

            // 2. 获取 fileId
            String fileId = Convert.toStr(metadata.get("fileId"));
            if (StrUtil.isBlank(fileId)) {
                return false;
            }

            // 3. 安全解析 chunkId
            int chunkId = (int) metadata.get("chunkId");

            boolean hasPermission = milvusAclManager.getFileChunkAcl(fileId,chunkId);
            // 4. 权限判断：用户有权限 OR 文档公开

            boolean isPublic = "public".equalsIgnoreCase(Convert.toStr(metadata.get("visibility")));
            return hasPermission || isPublic;
        } catch (Exception e) {
            log.error("权限校验异常", e);
            return false;
        }
    }
}