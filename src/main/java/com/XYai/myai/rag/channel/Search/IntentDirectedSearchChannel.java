package com.XYai.myai.rag.channel.Search;

import cn.hutool.core.collection.CollUtil;
import com.XYai.myai.rag.aop.Annotation.RagTraceNode;
import com.XYai.myai.rag.channel.POJO.RetrievedChunk;
import com.XYai.myai.rag.channel.POJO.SearchChannel;
import com.XYai.myai.rag.channel.POJO.SearchChannelResult;
import com.XYai.myai.rag.channel.POJO.SearchContext;
import com.XYai.myai.rag.milvus.MilvusCollectionService;
import com.XYai.myai.rag.intent.POJO.IntentNode;
import com.XYai.myai.rag.intent.POJO.SubQuestionIntent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class IntentDirectedSearchChannel implements SearchChannel {
    //意图检索,收到意图树中的节点ID,例如root-chat-farewell
    //根据节点ID在向量数据库中直接检索

    @Resource
    private VectorStore vectorStore;

    @Override
    public String getName() {
        return "intent-directed-search";
    }

    @Override
    public int getPriority() {
        return 1;  // 优先级最高，优先执行
    }

    @Override
    public String getType() {
        return "intent-directed-search";
    }


    @Override
    public boolean isEnabled(SearchContext context) {
        // 启用条件：有明确的KB意图（即需要检索知识库的意图）并且意图置信度足够高
        return CollUtil.isNotEmpty(context.getKbIntents());
    }

    @Override
    @RagTraceNode(name = "召回", type = "search")
    public SearchChannelResult search(SearchContext context) {
        log.info(String.valueOf(context));
        // 1. 提取用户问题的意图节点Id
        List<SubQuestionIntent> kbIntents = context.getKbIntents();
        if (CollUtil.isEmpty(kbIntents)) {
            return new SearchChannelResult();
        }
        // 当前意图模型：SubQuestionIntent -> List<IntentNode>(意图Id)
        //获取TopK
        Map<String, Integer> collectionTopK = extractCollectionTopK(kbIntents);
        if (collectionTopK.isEmpty()) {
            return new SearchChannelResult();
        }
        // 先按意图提取collection 列表，作为意图定向召回目标。
        String query = context.getQuestion();
        //召回结果
        List<RetrievedChunk> chunks = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : collectionTopK.entrySet()) {
            //根据CollectionName + metadata过滤元素
            String collectionName = entry.getKey();
            int topK = entry.getValue();
            //添加到召回结果
            chunks.addAll(searchOneCollection(collectionName, query, topK));
        }
        // 合并结果返回召回对象
        return SearchChannelResult.builder()
                .channelName(getName())
                .chunks(chunks)
                .metadata(Map.of("collections", collectionTopK.keySet()))
                .build();
    }

    // 并行检索多个Collection
    private Map<String, Integer> extractCollectionTopK(List<SubQuestionIntent> kbIntents) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (CollUtil.isEmpty(kbIntents)) {
            return result;
        }
        //遍历意图结果,获取TopK
        for (SubQuestionIntent subQuestionIntent : kbIntents) {
            if (subQuestionIntent == null || CollUtil.isEmpty(subQuestionIntent.getSubIntent())) {
                continue;
            }
            //遍历意图树节点获取
            for (IntentNode node : subQuestionIntent.getSubIntent()) {
                if (node == null) {
                    continue;
                }
                int nodeTopK = (node.getTopK() != null && node.getTopK() > 0) ? node.getTopK() : 5;
                String collectionName = node.getCollectionName();
                if (collectionName == null || collectionName.isBlank()) {
                    continue;
                }
                result.merge(collectionName, nodeTopK, Math::max);
            }
        }
        return result;
    }

    private List<RetrievedChunk> searchOneCollection(String collectionName, String query, int topK) {
        int effectiveTopK = Math.max(topK, 1);
        try {
            // 先判断是否加载再查
            if (vectorStore == null) {
                log.info("集合未加载:{}",collectionName);
                return List.of();
            }
            List<Document> documents = vectorStore
                    .similaritySearch(SearchRequest.builder()
                            .query(query)
                            .topK(effectiveTopK)
                            .build());
            if (documents == null || documents.isEmpty()) {
                return List.of();
            }
            //返回结果
            List<RetrievedChunk> chunks = new ArrayList<>();
            //遍历查找的内容
            for (Document doc : documents) {
                if (doc == null) {
                    continue;
                }

                Map<String, Object> metadata = new HashMap<>(doc.getMetadata());

                String id = String.valueOf(
                        metadata.getOrDefault("chunkId",
                                metadata.getOrDefault("id", UUID.randomUUID().toString()))
                );

                Double score = extractScore(metadata);

                chunks.add(RetrievedChunk.builder()
                        .id(id)
                        .collectionName(collectionName)
                        .content(doc.getText())
                        .score(score)
                        .metadata(metadata)
                        .build());
            }
            return chunks;
        } catch (Exception e) {
            log.warn("Milvus collection '{}' 匹配失败, skip: {}", collectionName, e.getMessage());
            return List.of();
        }
    }

    private Double extractScore(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }

        Object score = metadata.get("score");
        if (score instanceof Number n) {
            return n.doubleValue();
        }

        Object distance = metadata.get("distance");
        if (distance instanceof Number n) {
            // 如果底层返回的是 distance，你可以按需要换算
            return 1.0 - n.doubleValue();
        }

        return null;
    }


}