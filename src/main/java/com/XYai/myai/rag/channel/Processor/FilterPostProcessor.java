package com.XYai.myai.rag.channel.Processor;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.XYai.myai.rag.channel.POJO.RetrievedChunk;
import com.XYai.myai.rag.channel.POJO.SearchContext;
import com.XYai.myai.redis.RedisKeyConfig;
import com.XYai.myai.user.LoginUserInfoManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hsmf.datatypes.Chunks;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
        // 空输入短路
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        Long userId = LoginUserInfoManager.get().getId();
        if (userId == null) {
            log.warn("FilterPostProcessor: 未登录用户执行过滤，返回空结果");
            return List.of();
        }

        return chunks.stream()
                .filter(Objects::nonNull)                                  // 1. 空对象过滤
                .filter(FilterPostProcessor::filterComplete)                   // 2. 数据完整性过滤
                .filter(this::filterScore)                      // 3. 分数阈值过滤
                .filter(chunk -> filterPermission(chunk, userId))        // 4. 权限校验过滤
                .filter(this::filterContentLength)             // 6. 内容长度合规过滤
                .toList();
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
        return score > 0.5 && bm25Score > 0.5;
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
            Long chunkId = Convert.toLong(metadata.get("chunkId"));
            if (chunkId == null) {
                log.warn("无法解析 chunkId: {}", metadata.get("chunkId"));
                return false;
            }

            // 4. 权限判断：用户有权限 OR 文档公开
            boolean hasPermission = Optional.ofNullable(redissonClient.getBitSet(RedisKeyConfig.userFileBitKey(userId, fileId)))
                    .map(bitSet -> bitSet.isExists() && bitSet.get(chunkId))
                    .orElse(false);

            boolean isPublic = "public".equalsIgnoreCase(Convert.toStr(metadata.get("visibility")));
            return hasPermission || isPublic;
        } catch (Exception e) {
            log.error("权限校验异常", e);
            return false;
        }
    }
}