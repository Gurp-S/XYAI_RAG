package com.XYai.myai.rag.channel.Search;


import com.XYai.myai.mapper.ChatConversationMapper;
import com.XYai.myai.rag.channel.POJO.RetrievedChunk;
import com.XYai.myai.rag.channel.POJO.SearchChannel;
import com.XYai.myai.rag.channel.POJO.SearchChannelResult;
import com.XYai.myai.rag.channel.POJO.SearchContext;
import com.XYai.myai.rag.channel.Processor.BM25PostProcessor;
import com.XYai.myai.rag.memory.POJO.ChatConversation;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;


@Service
@Component
public class MemorySearchChannel implements SearchChannel {


    Double MIN_MEMORY_BM25_SCORE = 0.6;
    @Resource
    private BM25PostProcessor bm25PostProcessor;
    @Resource
    private ChatConversationMapper chatConversationMapper;

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
        // 当当前对话有持久化记录的时候进行
        if (context == null || context.getConversationId() == null) return false;
        QueryWrapper<ChatConversation> wrapper = new QueryWrapper<>();
        wrapper.eq("conversation_id", context.getConversationId());
        return !chatConversationMapper.selectList(wrapper).isEmpty();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        if (context == null || context.getConversationId() == null) {
            return SearchChannelResult.builder().channelName(getName()).chunks(List.of()).build();
        }
        // 搜索
        QueryWrapper<ChatConversation> wrapper = new QueryWrapper<>();
        wrapper.eq("conversation_id", context.getConversationId());
        wrapper.orderByAsc("created_at");
        List<ChatConversation> chatSessionRecords = chatConversationMapper.selectList(wrapper);
        // 拼接对话为RetrievedChunk
        List<RetrievedChunk> conversationRecord = chatSessionToRetrievedChunks(chatSessionRecords);
        // 打分返回
        List<RetrievedChunk> conversationRecordProcessor = bm25PostProcessor.process(conversationRecord, context);
        List<RetrievedChunk> conversationRecordWithScore = conversationRecordProcessor.stream()
                .filter(rc -> rc != null && rc.getScore() != null && rc.getScore() >= MIN_MEMORY_BM25_SCORE)
                .toList();
        return SearchChannelResult.builder().channelName(getName()).chunks(conversationRecordWithScore).build();
    }

    private List<RetrievedChunk> chatSessionToRetrievedChunks(List<ChatConversation> chatSessionRecords) {
        if (chatSessionRecords == null || chatSessionRecords.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> transResult = new ArrayList<>();
        for (ChatConversation chatSessionRecord : chatSessionRecords) {
            if (chatSessionRecord == null) continue;
            String userMsg = chatSessionRecord.getUserMessage() == null ? "" : chatSessionRecord.getUserMessage();
            String assistantMsg = chatSessionRecord.getAssistantMessage() == null ? "" : chatSessionRecord.getAssistantMessage();
            String content = "user:" + userMsg + " assistant:" + assistantMsg;
            Map<String, Object> metadata = new HashMap<>();
            if (chatSessionRecord.getConversationId() != null) {
                metadata.put("conversationId", chatSessionRecord.getConversationId());
            }
            if (chatSessionRecord.getChatMessageId() != null) {
                metadata.put("chatMessageId", chatSessionRecord.getChatMessageId());
            }
            if (chatSessionRecord.getCreatedAt() != null) {
                metadata.put("createdAt", chatSessionRecord.getCreatedAt().toString());
            }
            String id = (chatSessionRecord.getConversationId() == null ? "" : chatSessionRecord.getConversationId())
                    + "-"
                    + (chatSessionRecord.getChatMessageId() == null ? "" : chatSessionRecord.getChatMessageId());
            RetrievedChunk chunk = RetrievedChunk.builder()
                    .id(id)
                    .collectionName("Chat")
                    .content(content)
                    .metadata(metadata)
                    .build();

            transResult.add(chunk);
        }
        return transResult;
    }
}
