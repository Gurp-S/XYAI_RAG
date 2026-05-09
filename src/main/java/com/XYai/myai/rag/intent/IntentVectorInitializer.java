package com.XYai.myai.rag.intent;

import com.XYai.myai.mapper.IntentNodeMapper;
import com.XYai.myai.rag.intent.pojo.IntentNode;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class IntentVectorInitializer {

    @Autowired
    private IntentNodeMapper intentNodeMapper;

    @Autowired
    private VectorStore vectorStore;

    public void storeIntentNodes() {
        List<IntentNode> nodes = intentNodeMapper.selectList(new QueryWrapper<>());
        if (nodes == null || nodes.isEmpty()) {
            log.info("数据库中未找到意图节点，跳过向量化存储");
            return;
        }

        List<Document> docs = new ArrayList<>(nodes.size());
        for (IntentNode node : nodes) {
            // 生成向量化文本
            String text = node.getName();
            if (node.getParentName() != null && !node.getParentName().isBlank()) {
                text = node.getParentName() + " > " + text;
            }

            // 安全放入 metadata，过滤 null 值
            Map<String, Object> meta = new HashMap<>();
            safePut(meta, "nodeId", node.getNodeId());
            safePut(meta, "name", node.getName());
            safePut(meta, "parentName", node.getParentName());
            safePut(meta, "collectionName", node.getCollectionName());
            safePut(meta, "topK", node.getTopK());
            safePut(meta, "childrenCount", node.getChildrenCount());

            Document doc = Document.builder()
                    .id(node.getNodeId())
                    .text(text)
                    .metadata(meta)
                    .build();
            docs.add(doc);
        }

        vectorStore.add(docs);
        log.info("成功将 {} 条意图节点写入 Milvus", docs.size());
    }

    private void safePut(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}