package com.XYai.myai;

import com.XYai.myai.mapper.IntentNodeMapper;
import com.XYai.myai.rag.intent.POJO.IntentNode;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest
class MyAiApplicationTests {

    @Autowired
    private IntentNodeMapper intentNodeMapper;

    @Autowired
    private VectorStore vectorStore;

    /**
     * 一次性执行：从数据库读取所有意图节点，生成 Embedding 并存入 Milvus。
     * 运行该测试即可完成同步，后续意图识别可直接使用向量检索。
     */
    @Test
    public void storeIntentNodesToVectorStore() {
        // 1. 从数据库加载全部意图节点
        List<IntentNode> nodes = intentNodeMapper.selectList(new QueryWrapper<>());
        if (nodes == null || nodes.isEmpty()) {
            System.out.println("数据库中未找到意图节点，跳过存储");
            return;
        }

        // 2. 转换为 Spring AI Document 列表
        List<Document> docs = new ArrayList<>(nodes.size());
        for (IntentNode node : nodes) {
            // 用于生成向量的文本：节点名称 + 父节点名（增强区分度）
            String text = node.getName();
            if (node.getParentName() != null && !node.getParentName().isBlank()) {
                text = node.getParentName() + " > " + text;
            }

            Map<String, Object> meta = new HashMap<>();
            meta.put("nodeId", node.getNodeId());
            meta.put("name", node.getName());
            meta.put("parentName", node.getParentName());
            meta.put("collectionName", node.getCollectionName());
            meta.put("topK", node.getTopK());
            meta.put("childrenCount", node.getChildrenCount());
            // 可根据需要补充其他字段

            // 以 nodeId 作为 Document ID，确保幂等性（重复执行不会产生重复记录）
            docs.add(new Document(text, meta, node.getNodeId()));
        }

        // 3. 批量写入 Milvus（VectorStore 内部自动调用 EmbeddingModel 生成向量）
        vectorStore.add(docs);
        System.out.println("成功将 " + docs.size() + " 条意图节点写入 Milvus");
    }
}