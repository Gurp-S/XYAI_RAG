package com.XYai.myai.rag.etlpipeline.consumers;

import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.milvus.MilvusAclManager;
import com.XYai.myai.rag.milvus.MilvusMetadataFilter;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.dml.InsertParam;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;

@Slf4j
@Component
public class Indexer{
    @Resource
    private MilvusAclManager milvusAclManager;

    @Resource
    private MilvusServiceClient milvusClient;

    @Resource
    private MilvusMetadataFilter milvusMetadataFilter;

    @Resource
    private EmbeddingModel embeddingModel;

    @Value("${spring.ai.vectorstore.milvus.collectionName:my_ai}")
    private String defaultCollectionName;

    @Value("${spring.ai.vectorstore.milvus.databaseName:my_xy}")
    private String databaseName;


    @RagTraceNode(name = "执行入库", type = "上传管道",taskIdArg = "etlNode")
    public void execute(Document chunk,String collectionName) {
        // TODO if (!Boolean.TRUE.equals(uploadProperties.getRagEnabled())) {}

        try {
            if (!Boolean.TRUE.equals(milvusAclManager.getCollectionAcl(collectionName))) {
                log.warn("集合无ACL权限或不存在,跳过入库: collectionName={}", collectionName);
                //TODO 无权限操作
                return;
            }
            log.info("Indexer开始入库: collectionName={}, doc_id={}", collectionName, chunk.getId());

            //入库
            processChunk(chunk);

        } catch (Exception e) {
            log.error("向量入库失败 → 集合名: {}", collectionName, e);
            throw new RuntimeException("Milvus 插入失败", e);
        }
    }

    private void processChunk(Document chunk) {
        String originalText = chunk.getText();

        // 1. 获取假设性问题文本
        String question = null;
        Object questionObj = chunk.getMetadata().get("hypothetical_questions");
        if (questionObj != null) {
            question = questionObj.toString();
        }
        if (!StringUtils.hasText(question)) {
            question = originalText;
        }

        // 1.5 Contextual Retrieval：embedding_context 使用 上下文前缀+原文，content 保持原文
        String contextEmbedText = originalText;
        Object contextPrefixObj = chunk.getMetadata().get("context_prefix");
        if (contextPrefixObj != null && StringUtils.hasText(contextPrefixObj.toString())) {
            contextEmbedText = contextPrefixObj + "\n" + originalText;
        }

        // 2. 生成向量（返回 float[]）
        float[] questionVectorArray = null;
        if (question != null) {
            questionVectorArray = embeddingModel.embed(question);
            if (questionVectorArray.length == 0) {
                throw new RuntimeException("embedding返回空，问题文本：" + question);
            }
        }
        float[] contextVectorArray = null;
        if (contextEmbedText != null) {
            contextVectorArray = embeddingModel.embed(contextEmbedText);
            if (contextVectorArray.length == 0) {
                throw new RuntimeException("embedding返回空，原始文本：" + contextEmbedText);
            }
        }

        // 3. 转换为 Milvus 需要的 List<Float>
        List<Float> questionVector = toFloatList(questionVectorArray);
        List<Float> contextVector = toFloatList(contextVectorArray);

        // 4. 过滤 metadata 并转为 JSON 字符串
        Map<String, Object> filteredMeta = milvusMetadataFilter.filter(chunk.getMetadata());
        String metadataJson = new Gson().toJson(filteredMeta);
        JsonObject metadataObj = new Gson().fromJson(metadataJson, JsonObject.class);

        // 5. 更新 Document（保留内容，仅覆盖 metadata）
        Document document = chunk.mutate()
                .metadata(filteredMeta)
                .build();

        // 6. 构建插入字段（注意向量与字段名的正确对应）
        List<InsertParam.Field> fields = Arrays.asList(
                new InsertParam.Field("doc_id", Collections.singletonList(document.getId())),
                new InsertParam.Field("content", Collections.singletonList(document.getText())),
                new InsertParam.Field("embedding_question", Collections.singletonList(questionVector)),
                new InsertParam.Field("embedding_context", Collections.singletonList(contextVector)),
                new InsertParam.Field("metadata", Collections.singletonList(metadataObj))
        );

        InsertParam insertParam = InsertParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(defaultCollectionName)
                .withFields(fields)
                .build();

        milvusClient.insert(insertParam);
    }

    // 工具方法：float[] → List<Float>
    private List<Float> toFloatList(float[] array) {
        if (array == null) return Collections.emptyList();
        List<Float> list = new ArrayList<>(array.length);
        for (float v : array) {
            list.add(v);
        }
        return list;
    }
}