package com.XYai.myai.rag.channel.processor;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.XYai.myai.commonUtils.redis.RedisKeyConfig;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.channel.pojo.RetrievalProperties;
import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.channel.pojo.SearchContext;
import com.XYai.myai.rag.milvus.MilvusAclManager;
import com.XYai.myai.user.LoginUserInfoManager;
import com.github.benmanes.caffeine.cache.Cache;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 过滤后处理器。
 * <p>
 * 定位：召回后处理中间阶段（去重之后、重排之前），负责做“硬规则”剔除。
 * 目标：把明显不合格候选提前过滤，避免污染 Rerank 输入。
 * </p>
 */
@Slf4j
@Component
public class FilterPostProcessor implements SearchResultPostProcessor {

    private static final String NAME = "filter-processor";
    private final StringRedisTemplate stringRedisTemplate;
    @Resource
    private RetrievalProperties retrievalProperties;
    @Resource
    private MilvusAclManager milvusAclManager;
    @Resource
    @Qualifier("defaultCache")
    private Cache<String, Object> localCache;

    public FilterPostProcessor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static String resolveDocId(RetrievedChunk chunk) {
        if (chunk == null) return null;
        String id = chunk.getId();
        if (StrUtil.isNotBlank(id)) return id;
        Map<String, Object> meta = chunk.getMetadata();
        if (meta == null) return null;
        Object v = meta.get("doc_id");
        if (v == null) return null;
        String s = String.valueOf(v);
        return StrUtil.isBlank(s) ? null : s;
    }

    private static DocIdParts parseDocId(String docId) {
        if (StrUtil.isBlank(docId)) return null;
        int idx = docId.lastIndexOf(':');
        if (idx <= 0 || idx >= docId.length() - 1) return null;
        String fileId = docId.substring(0, idx);
        String chunkStr = docId.substring(idx + 1);
        if (StrUtil.isBlank(fileId) || StrUtil.isBlank(chunkStr)) return null;
        try {
            int chunkId = Integer.parseInt(chunkStr);
            return new DocIdParts(fileId, chunkId);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Override
    public String getName() {
        return NAME;
    }

    // ========== 内部辅助类 ==========
    @Override
    public int getOrder() {
        return 5; // 在去重之后，Rerank之前
    }

    @Override
    @RagTraceNode(name = "过滤处理", type = "process",taskIdArg = "processRoot")
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks, SearchContext context) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        Long userId = LoginUserInfoManager.getUserId();
        if (userId == null) {
            log.warn("FilterPostProcessor: 未登录用户执行过滤，返回空结果");
            return List.of();
        }

        // 预加载用户已授权文件ID集合（仅用于私有文档）
        Set<String> authorizedFileIds = loadAuthorizedFileIds(userId);

        // 用于文件级去重（每个文件仅保留第一个通过的chunk）
        Map<String, Boolean> fileExistMap = new HashMap<>();

        // 先解析每个chunk的docId和parts，缓存避免重复解析
        return chunks.stream()
                .filter(Objects::nonNull)
                .map(chunk -> {
                    String docId = resolveDocId(chunk);
                    DocIdParts parts = parseDocId(docId);
                    return new ChunkWithMeta(chunk, docId, parts);
                })
                .filter(this::filterComplete)
                .filter(meta -> filterDistinctFileChunk(meta, fileExistMap))
                .filter(meta -> filterContentLength(meta.chunk()))
                .filter(meta -> filterScore(meta.chunk()))
                // 公开文档直接放行，私有文档才进入后续检查
                .filter(meta -> {
                    if (isPublicDocument(meta.chunk())) {
                        log.info("公开文档放行: docId={}", meta.docId());
                        return true;
                    }
                    // 私有文档：检查授权集合
                    if (!filterUnloadCollection(meta, authorizedFileIds)) {
                        log.info("私有文档授权集合检查失败: docId={}", meta.docId());
                        return false;
                    }
                    // 私有文档：检查细粒度权限
                    if (!filterPermission(meta)) {
                        log.info("私有文档权限检查失败: docId={}", meta.docId());
                        return false;
                    }
                    return true;
                })
                .map(ChunkWithMeta::chunk)
                .toList();
    }

    // ========== 预加载授权文件ID ==========
    private Set<String> loadAuthorizedFileIds(Long userId) {
        String cacheKey = "user:fileIdSet" + userId;
        @SuppressWarnings("unchecked")
        Set<String> cached = (Set<String>) localCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        Set<String> authorizedFileIds = new HashSet<>();
        try {
            Set<String> loadCollections = stringRedisTemplate.opsForSet().members(RedisKeyConfig.userLoadCollectionsKey(userId));
            if (loadCollections != null && !loadCollections.isEmpty()) {
                for (String collectionName : loadCollections) {
                    Set<String> fileChunkSet = stringRedisTemplate.opsForSet().members(RedisKeyConfig.collectionFileIds(collectionName));
                    if (fileChunkSet != null && !fileChunkSet.isEmpty()) {
                        for (String fileChunkId : fileChunkSet) {
                            if (StrUtil.isNotBlank(fileChunkId)) {
                                String fileId = extractFileIdFromChunkId(fileChunkId);
                                if (fileId != null) {
                                    authorizedFileIds.add(fileId);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("加载用户授权文件ID失败，userId={}", userId, e);
        }

        localCache.put(cacheKey, authorizedFileIds);
        return authorizedFileIds;
    }

    private String extractFileIdFromChunkId(String fileChunkId) {
        if (StrUtil.isBlank(fileChunkId)) return null;
        int idx = fileChunkId.lastIndexOf(':');
        if (idx <= 0) return null;
        return fileChunkId.substring(0, idx);
    }

    // ========== 过滤条件实现 ==========
    private boolean isPublicDocument(RetrievedChunk chunk) {
        Map<String, Object> metadata = chunk.getMetadata();
        return !MapUtil.isEmpty(metadata)
                && "public".equalsIgnoreCase(Convert.toStr(metadata.get("visibility")));
    }

    private boolean filterComplete(ChunkWithMeta meta) {
        if (meta.chunk() == null || meta.chunk().getContent() == null) {
            log.info("[filterComplete] 丢弃: content为空");
            return false;
        }
        if (meta.parts() == null || StrUtil.isBlank(meta.parts().fileId()) || meta.parts().chunkId() == null) {
            log.info("[filterComplete] 丢弃: docId解析失败, docId={}", meta.docId());
            return false;
        }
        // 内容不能全是空白
        if (StrUtil.isBlank(meta.chunk().getContent())) {
            log.info("[filterComplete] 丢弃: content为空白字符串");
            return false;
        }
        return true;
    }

    private boolean filterContentLength(RetrievedChunk chunk) {
        String content = chunk.getContent();
        if (content == null) return false;
        int len = content.length();
        boolean valid = len >= retrievalProperties.getMinContentLength() && len <= retrievalProperties.getMaxContentLength();
        if (!valid) {
            log.info("[filterContentLength] 丢弃: length={}", len);
        }
        return valid;
    }

    private boolean filterScore(RetrievedChunk chunk) {
        Double score = chunk.getScore();
        if (score == null) return false;

        Double bm25Score = chunk.getBm25Score();
        // 纯向量召回（无BM25分）
        if (bm25Score == null) {
            boolean pass = score > retrievalProperties.getVectorOnlyThreshold();
            if (!pass) log.info("[filterScore] 纯向量召回丢弃: score={}", score);
            return pass;
        }

        double bm25 = bm25Score;
        boolean commonScore = score > retrievalProperties.getCommonScoreThreshold() && bm25 > retrievalProperties.getCommonScoreThreshold();
        boolean highAndLowScore = (score > retrievalProperties.getHighScoreThreshold() && bm25 > retrievalProperties.getLowScoreThreshold())
                || (bm25 > retrievalProperties.getHighScoreThreshold() && score > retrievalProperties.getLowScoreThreshold());
        boolean pass = commonScore || highAndLowScore;
        if (!pass) {
            log.info("[filterScore] 丢弃: score={}, bm25={}", score, bm25);
        }
        return pass;
    }

    /**
     * 私有文档的授权集合检查（用户是否加载了包含该文件任何chunk的集合）
     */
    private boolean filterUnloadCollection(ChunkWithMeta meta, Set<String> authorizedFileIds) {
        if (meta.parts() == null || StrUtil.isBlank(meta.parts().fileId())) {
            return false;
        }
        return authorizedFileIds.contains(meta.parts().fileId());
    }


    // ========== 静态工具方法 ==========

    /**
     * 私有文档的细粒度权限检查
     */
    private boolean filterPermission(ChunkWithMeta meta) {
        try {
            String fileId = meta.parts().fileId();
            Integer chunkId = meta.parts().chunkId();
            if (fileId == null || chunkId == null) return false;
            return milvusAclManager.getFileChunkAcl(fileId, chunkId);
        } catch (Exception e) {
            log.error("权限校验异常，docId={}", meta.docId(), e);
            return false;
        }
    }

    /**
     * 文件级去重：每个文件只保留第一个通过的chunk
     */
    private boolean filterDistinctFileChunk(ChunkWithMeta meta, Map<String, Boolean> existMap) {
        if (meta.parts() == null || StrUtil.isBlank(meta.parts().fileId())) {
            // 无有效fileId的chunk（如summary/memory）不受去重限制
            return true;
        }
        String docId = meta.docId();
        if (existMap.containsKey(docId)) {
            log.info("[filterDistinctFileChunk] 丢弃: 文件 {} 已保留过", docId);
            return false;
        }
        existMap.put(docId, true);
        return true;
    }

    /**
     * 缓存解析结果，避免重复解析docId
     */
    private record ChunkWithMeta(RetrievedChunk chunk, String docId, DocIdParts parts) {

    }

    private record DocIdParts(String fileId, Integer chunkId) {

    }
}