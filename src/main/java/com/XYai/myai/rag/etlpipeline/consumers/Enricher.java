package com.XYai.myai.rag.etlpipeline.consumers;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.XYai.myai.rag.aop.annotation.RagTraceContext;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.chat.ModelInvocationService;
import com.XYai.myai.rag.kafka.event.AnalyticsEvent;
import com.XYai.myai.rag.kafka.event.Neo4jEvent;
import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.xyAdmin.pojo.TokenUse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class Enricher{

    private static final int MAX_INPUT_LENGTH = 2000;   // 分块文本通常较短

    @Value("${etl.contextual-retrieval-enabled:true}")
    private boolean contextualRetrievalEnabled;

    @Resource
    private ChatModel chatModel;
    @Resource
    private ModelInvocationService modelInvocation;
    @Resource
    private KafkaTemplate<String, Object> kafkaTemplate;

    @RagTraceNode(name = "增强", type = "上传管道" ,taskIdArg = "etlNode")
    public Document execute(Document chunk) {

        // TODO if(pipelineProperties.getEnricherEnable()){return NodeResult.ok("增强未开启，跳过增强");}
        if (chunk == null) {
            log.warn("Enricher: 输入 chunk 为 null，跳过增强");
            return null;
        }
        // 线程安全的收集器：chunkId -> 三元组文本
        Map<String, String> triplesMap = new ConcurrentHashMap<>();

        // 处理 chunk
        chunk = enrichChunk(chunk, triplesMap);

        // TODO Kafka一次性批量写入收集到的三元组
        kafkaTemplate.send("neo4j-cmd", null, new Neo4jEvent(
                UUID.randomUUID().toString(), "INSERT_TRIPLES",
                triplesMap, List.of(chunk.getId()))
        );

        return chunk;
    }

    private Document enrichChunk(Document chunk, Map<String, String> triplesMap) {
        String text = chunk.getText();
        if (text == null || text.isBlank()) {
            return chunk;
        }
        String promptInput = text.length() > MAX_INPUT_LENGTH
                ? text.substring(0, MAX_INPUT_LENGTH) : text;

        // 并行生成问题和三元组（LLM调用仍是并行）
        String questions = generateQuestions(promptInput);
        String triples = extractTriples(promptInput);
        // Contextual Retrieval（Anthropic, 2024）：为 chunk 生成定位上下文，入库前拼入 embedding 文本
        String contextPrefix = buildContextPrefix(chunk, promptInput);
        // TODO if (pipelineProperties.getEnricherQuestionEnable()) {}
        // TODO if (pipelineProperties.getEnricherTriplesEnable()) {}
        // 收集有效的三元组（非空且非“无”）
        Document mutated = chunk;
        if (!triples.isBlank() && !"无".equals(triples)) {
            triplesMap.put(chunk.getId(), triples);
        }
        if (StrUtil.isNotBlank(contextPrefix)) {
            mutated = chunk.mutate().metadata("context_prefix", contextPrefix).build();
        }
        // 注意：Indexer 读取的键为 hypothetical_questions（此前误写 questions 导致问题向量从未生效）
        return mutated.mutate()
                .metadata("hypothetical_questions", questions)
                .build();
    }

    /**
     * Contextual Retrieval：合成 chunk 级上下文前缀。
     * 结构 = 文档名 + 章节路径（确定性元信息） + LLM 生成的位置说明（50-100 token）。
     * 任一环节失败均降级为仅确定性元信息，不阻断入库。
     */
    private String buildContextPrefix(Document chunk, String chunkText) {
        Map<String, Object> meta = chunk.getMetadata();
        String fileName = meta == null ? null : Objects.toString(meta.get("fileName"), null);
        String sectionPath = meta == null ? null : Objects.toString(meta.get("section_path"), null);
        StringBuilder prefix = new StringBuilder();
        if (StrUtil.isNotBlank(fileName)) {
            prefix.append("本文档《").append(fileName).append("》");
        }
        if (StrUtil.isNotBlank(sectionPath)) {
            prefix.append("章节「").append(sectionPath).append("」");
        }
        String llmContext = generateChunkContext(chunkText);
        if (StrUtil.isNotBlank(llmContext) && !"无".equals(llmContext)) {
            prefix.append(llmContext);
        }
        return prefix.toString().strip();
    }

    /**
     * LLM 生成的 chunk 定位说明：交代该块在全文中的主题与关联背景。
     */
    private String generateChunkContext(String chunkText) {
        if (!contextualRetrievalEnabled) {
            return "";
        }
        String prompt = String.format("""
                你是文档检索优化助手。以下是一个从文档中切分出的文本块，请用一句话（不超过80字）说明该块在原文中的位置背景与讨论主题，用于提升该块被检索命中的概率。
                只输出这句话本身，不要任何前缀、解释或引号。
                文本块：
                %s
                """, chunkText);
        try {
            String resp = chatModel.call(prompt).trim();
            return (resp.isEmpty() || resp.startsWith("抱歉")) ? "" : resp;
        } catch (Exception e) {
            log.warn("生成 chunk 上下文说明失败，降级为确定性前缀", e);
            return "";
        }
    }


    // ======================== 私有方法 ========================

    private String generateQuestions(String fullText) {
        String prompt = String.format("""
                你是一个擅长提问的助手。请阅读以下文档，并生成1个用户最可能会对该文档内容提出的问题。
                要求：问题必须覆盖文档的核心信息；直接给出问题，不要编号，不要解释。
                文档：
                %s
                生成的问题：
                """, fullText);
        try {
            long t1 = System.currentTimeMillis();
            String resp = chatModel.call(prompt).trim();
            long cost = System.currentTimeMillis() - t1;
            RagTraceContext.setPhase("文档增强-问题");
            TokenUse tokenUse = new TokenUse(
                    IdUtil.getSnowflakeNextId(),
                    IdUtil.getSnowflakeNextId(),
                    prompt.length(),
                    resp.length(),
                    LoginUserInfoManager.getUserId(),
                    cost,
                    chatModel.getDefaultOptions().getModel(), "upload-enricher");
            kafkaTemplate.send("analytics-event", null, new AnalyticsEvent(
                    UUID.randomUUID().toString(),"evaluate",
                    null,tokenUse,null
            ));
            return (resp.isEmpty() || resp.startsWith("抱歉")) ? "无" : resp;
        } catch (Exception e) {
            log.error("生成问题失败", e);
            return "";
        }
    }

    private String extractTriples(String fullText) {
        String prompt = String.format("""
                你是一个知识图谱构建专家。请从以下文档中提取所有实体及其关系，格式：主体 | 关系 | 客体
                仅输出关系行，无则输出“无”。
                文档全文：%s
                """, fullText);
        try {
            long t1 = System.currentTimeMillis();
            String resp = chatModel.call(prompt).trim();
            long cost = System.currentTimeMillis() - t1;
            RagTraceContext.setPhase("文档增强-三元组");
            TokenUse tokenUse = new TokenUse(
                    IdUtil.getSnowflakeNextId(),
                    IdUtil.getSnowflakeNextId(),
                    prompt.length(),
                    resp.length(),
                    LoginUserInfoManager.getUserId(),
                    cost,
                    chatModel.getDefaultOptions().getModel(), "upload-enricher");
            kafkaTemplate.send("analytics-event", null, new AnalyticsEvent(
                    UUID.randomUUID().toString(),"evaluate",
                    null,tokenUse,null
            ));
            return (resp.isEmpty() || resp.startsWith("抱歉")) ? "无" : resp;
        } catch (Exception e) {
            log.error("提取三元组失败", e);
            return "无";
        }
    }
}