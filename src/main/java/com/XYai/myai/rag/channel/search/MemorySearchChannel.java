package com.XYai.myai.rag.channel.search;

import com.XYai.myai.mapper.ChatConversationMapper;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.channel.pojo.SearchChannel;
import com.XYai.myai.rag.channel.pojo.SearchChannelResult;
import com.XYai.myai.rag.channel.pojo.SearchContext;
import com.XYai.myai.rag.channel.processor.BM25PostProcessor;
import com.XYai.myai.rag.memory.pojo.ChatConversation;
import com.XYai.myai.rag.memory.pojo.MemoryProperties;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@Component
public class MemorySearchChannel implements SearchChannel {

    // BM25 原始分阈值（已废弃，改用融合分阈值）
    // private static final double MIN_MEMORY_BM25_SCORE = 0.6;

    @Resource
    private BM25PostProcessor bm25PostProcessor;
    @Resource
    private ChatConversationMapper chatConversationMapper;
    @Resource
    private MemoryProperties memoryProperties;

    // ---------- 融合打分可配置参数 ----------
    // 时间衰减系数 lambda (每小时衰减速度)
    @Value("${memory.decay.lambda:0.01}")
    private double timeDecayLambda;

    // 融合分阈值
    @Value("${memory.final.score.threshold:0.2}")
    private double minFinalScore;

    // 最大召回记忆条数
    @Value("${memory.max.recall.chunks:5}")
    private int maxMemoryChunks;

    // BM25 得分在融合中的权重（0~1）
    @Value("${memory.fusion.alpha:0.8}")
    private double alpha;

    @Override
    public String getName() {
        return "memory-search";
    }

    @Override
    public String getType() {
        return "memory";
    }

    @Override
    public int getPriority() {
        return 3;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        if (context == null || context.getConversationId() == null) return false;
        QueryWrapper<ChatConversation> wrapper = new QueryWrapper<>();
        wrapper.eq("conversation_id", context.getConversationId());
        // 仅当对话轮数超过配置的最少保留轮数时才启用记忆通道
        return chatConversationMapper.selectCount(wrapper) > memoryProperties.getHistoryKeepTurns();
    }

    @Override
    @RagTraceNode(name = "记忆召回", type = "search",taskIdArg = "searchRoot")
    public SearchChannelResult search(SearchContext context) {
        if (context == null || context.getConversationId() == null) {
            return SearchChannelResult.builder().channelName(getName()).chunks(List.of()).build();
        }

        // 1. 构建查询：仅当天记录，按时间降序，限制条数
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        int fetchLimit = memoryProperties.getHistoryKeepTurns() * 4;
        QueryWrapper<ChatConversation> wrapper = new QueryWrapper<>();
        wrapper.eq("conversation_id", context.getConversationId())
                .ge("created_at", todayStart)
                .orderByDesc("created_at")
                .last("LIMIT " + fetchLimit);
        List<ChatConversation> chatSessionRecords = chatConversationMapper.selectList(wrapper);

        // 2. 转换为 RetrievedChunk
        List<RetrievedChunk> conversationRecord = chatSessionToRetrievedChunks(chatSessionRecords);

        // 3. 用 BM25 处理器进行词频相关性打分
        List<RetrievedChunk> bm25ScoredChunks = bm25PostProcessor.process(conversationRecord, context);

        // 4. 融合时间衰减，计算最终得分
        List<RetrievedChunk> fusedChunks = applyTimeDecayAndFusion(bm25ScoredChunks);

        // 5. 过滤、排序、截断
        List<RetrievedChunk> finalChunks = fusedChunks.stream()
                .filter(rc -> rc.getScore() >= minFinalScore)
                .sorted(Comparator.comparingDouble(RetrievedChunk::getScore).reversed())
                .limit(maxMemoryChunks)
                .toList();

        // 6. 兜底策略：如果过滤后为空，则保留融合分最高的那一条（避免记忆通道空转）
        if (finalChunks.isEmpty() && !fusedChunks.isEmpty()) {
            finalChunks = fusedChunks.stream()
                    .max(Comparator.comparingDouble(RetrievedChunk::getScore))
                    .stream().toList();
            log.debug("记忆通道无 chunk 达到阈值 {}，已兜底保留最高分 chunk", minFinalScore);
        }

        log.info("记忆通道召回 {} 条记忆，最终返回 {} 条", conversationRecord.size(), finalChunks.size());
        return SearchChannelResult.builder()
                .channelName(getName())
                .chunks(finalChunks)
                .build();
    }

    /**
     * 对 BM25 得分进行归一化，并融合时间衰减因子，将最终得分写入 chunk.score
     */
    private List<RetrievedChunk> applyTimeDecayAndFusion(List<RetrievedChunk> chunks) {
        if (chunks.isEmpty()) return chunks;

        // 计算 BM25 得分的最大值、最小值，用于归一化
        double maxBm25 = chunks.stream()
                .mapToDouble(c -> c.getBm25Score() != null ? c.getBm25Score() : 0.0)
                .max().orElse(1.0);
        double minBm25 = chunks.stream()
                .mapToDouble(c -> c.getBm25Score() != null ? c.getBm25Score() : 0.0)
                .min().orElse(0.0);

        for (RetrievedChunk chunk : chunks) {
            double bm25 = chunk.getBm25Score() != null ? chunk.getBm25Score() : 0.0;
            // 归一化 BM25 到 [0,1]
            double bm25Norm = (maxBm25 == minBm25) ? 1.0 : (bm25 - minBm25) / (maxBm25 - minBm25);
            // 提取时间戳（毫秒）
            long timestamp = extractTimestamp(chunk);
            double decay = timeDecay(timestamp);
            // 融合得分：alpha * BM25归一化 * 时间衰减
            double finalScore = alpha * bm25Norm * decay + (1 - alpha) * 0.0;
            chunk.setScore(finalScore);
        }
        return chunks;
    }

    /**
     * 时间衰减函数：指数衰减，越久远的消息得分越低
     */
    private double timeDecay(long messageTimestamp) {
        long now = System.currentTimeMillis();
        double hours = (now - messageTimestamp) / (1000.0 * 3600.0);
        // 避免负值
        if (hours < 0) hours = 0;
        return Math.exp(-timeDecayLambda * hours);
    }

    /**
     * 从 chunk 的 metadata 中提取时间戳（毫秒），若失败则返回当前时间
     */
    private long extractTimestamp(RetrievedChunk chunk) {
        Object tsObj = chunk.getMetadata() != null ? chunk.getMetadata().get("createdAtTimestamp") : null;
        if (tsObj instanceof Number) {
            return ((Number) tsObj).longValue();
        }
        // 如果未存时间戳，尝试解析字符串（兼容旧数据，但建议直接存时间戳）
        Object strObj = chunk.getMetadata() != null ? chunk.getMetadata().get("createdAt") : null;
        if (strObj != null) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(strObj.toString(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (Exception e) {
                log.debug("无法解析 createdAt 字符串: {}", strObj);
            }
        }
        // 兜底：返回当前时间，衰减系数为 1
        return System.currentTimeMillis();
    }

    /**
     * 将 ChatConversation 列表转为 RetrievedChunk，并在 metadata 中保存时间戳（毫秒）
     */
    private List<RetrievedChunk> chatSessionToRetrievedChunks(List<ChatConversation> chatSessionRecords) {
        if (chatSessionRecords == null || chatSessionRecords.isEmpty()) {
            return List.of();
        }

        List<RetrievedChunk> transResult = new ArrayList<>();
        for (ChatConversation record : chatSessionRecords) {
            if (record == null) continue;

            String userMsg = Objects.toString(record.getUserMessage(), "");
            String assistantMsg = Objects.toString(record.getAssistantMessage(), "");
            String content = "user: " + userMsg + "\nassistant: " + assistantMsg;

            Map<String, Object> metadata = new HashMap<>();
            if (record.getConversationId() != null) {
                metadata.put("conversationId", record.getConversationId());
            }
            if (record.getChatMessageId() != null) {
                metadata.put("chatMessageId", record.getChatMessageId());
            }
            // 存入原始创建时间（可读）
            if (record.getCreatedAt() != null) {
                metadata.put("createdAt", record.getCreatedAt().toString());
                Instant instant = record.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant();
                metadata.put("createdAtTimestamp", instant.toEpochMilli());
            }

            String id = Objects.toString(record.getConversationId(), "")
                    + "-" + Objects.toString(record.getChatMessageId(), "");

            transResult.add(RetrievedChunk.builder()
                    .id(id)
                    .content(content)
                    .metadata(metadata)
                    .build());
        }
        return transResult;
    }
}