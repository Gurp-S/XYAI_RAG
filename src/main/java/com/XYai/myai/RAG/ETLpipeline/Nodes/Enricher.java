package com.XYai.myai.RAG.ETLpipeline.Nodes;

import com.XYai.myai.RAG.ETLpipeline.POJO.IngestionContext;
import com.XYai.myai.RAG.ETLpipeline.POJO.NodeConfig;
import com.XYai.myai.RAG.ETLpipeline.POJO.NodeResult;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 文档增强节点（ETL 流程中的 enricher 环节）
 * 功能：调用大模型对文档文本进行增强、摘要、结构化处理
 * 处理结果存入元数据 META_ENHANCED_TEXT
 */
@Slf4j
@Component
public class Enricher implements Ingestion {

    /**
     * 注入 Spring AI 对话模型（大模型客户端）
     */
    @Resource
    private ChatModel chatModel;

    /**
     * 返回当前节点类型：enricher（增强节点）
     */
    @Override
    public String getNodeType() {
        return "enricher";
    }

    /**
     * 执行增强节点的核心逻辑
     * @param context 文档摄取上下文（包含 document）
     * @param config 节点配置（mode、字数限制等）
     * @return 节点执行结果
     */
    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        // 1. 获取文档，判空
        Document document = context.getDocument();
        if (document == null) {
            return NodeResult.ok("没有可增强的文本");
        }

        // 2. 获取文档原文，判空
        String originalText = document.getText();
        if (!StringUtils.hasText(originalText)) {
            return NodeResult.ok("没有可增强的文本");
        }

        // 3. 检查元数据中是否已经存在增强文本（避免重复处理）
        Object enhancedValue = document.getMetadata().get(IngestionContext.META_ENHANCED_TEXT);
        String enhancedText = enhancedValue == null ? null : String.valueOf(enhancedValue);

        if (StringUtils.hasText(enhancedText)) {
            return NodeResult.ok("增强文本已存在");
        }

        // 4. 调用 LLM 执行文本增强
        String result = enhanceText(originalText, config);

        // 5. 增强失败 → 保存原文作为兜底
        if (!StringUtils.hasText(result)) {
            document.getMetadata().put(IngestionContext.META_ENHANCED_TEXT, originalText);
            return NodeResult.ok("增强失败，已回退原文");
        }

        // 6. 增强成功 → 保存增强后的文本到元数据
        document.getMetadata().put(IngestionContext.META_ENHANCED_TEXT, result);
        return NodeResult.ok("增强文本=" + result.length());
    }

    /**
     * 调用大模型执行文本增强
     * @param text 原始文本
     * @param config 节点配置
     * @return 增强后的文本（失败则返回原文）
     */
    private String enhanceText(String text, NodeConfig config) {
        try {
            // 1. 读取节点配置：增强模式、输入最大长度、输出最大长度
            JsonNode settings = config == null ? null : config.getSettings();
            String mode = "rewrite"; // 默认：重写增强

            // 从配置中读取 mode
            if (settings != null && settings.has("mode") && settings.get("mode").isTextual()) {
                String value = settings.get("mode").asText();
                if (StringUtils.hasText(value)) {
                    mode = value;
                }
            }

            // 读取最大输入字符数（默认 5000）
            int maxInputChars = readInt(settings, "maxInputChars", 5000);
            // 读取最大输出字符数（默认 2000）
            int maxOutputChars = readInt(settings, "maxOutputChars", 2000);

            // 2. 截断超长文本，避免 token 超限
            String finalText = text.length() > maxInputChars ? text.substring(0, maxInputChars) : text;

            // 3. 构建提示词（根据 mode 生成不同系统提示）
            Prompt enhancedPrompt = getPrompt(finalText, mode, maxOutputChars);

            // 4. 调用大模型
            String enhanced = chatModel.call(enhancedPrompt).getResult().getOutput().getText();

            // 5. 模型返回空 → 回退原文
            if (!StringUtils.hasText(enhanced)) {
                return finalText;
            }

            // 6. 返回增强结果（去空格）
            return enhanced.trim();

        } catch (Exception ex) {
            // 异常捕获：调用失败 → 回退原文
            log.warn("增强节点调用失败, 回退原文", ex);
            return text;
        }
    }

    /**
     * 根据增强模式构建 LLM 提示词（Prompt）
     * @param text 待处理文本
     * @param mode 模式：summary / structure / rewrite
     * @param maxOutputChars 最大输出字数
     * @return Prompt 对象
     */
    private Prompt getPrompt(String text, String mode, int maxOutputChars) {
        // 根据模式选择系统提示词
        String systemMessage = switch (mode == null ? "rewrite" : mode.toLowerCase()) {
            // 摘要模式：生成简洁摘要
            case "summary" ->
                    "你是文档摘要助手。请基于给定文本生成准确、简洁的摘要，不要编造事实，输出不超过 " + maxOutputChars + " 字。";
            // 结构化模式：整理成标题、要点、关键词
            case "structure" ->
                    "你是文档结构化助手。请把给定文本整理成标题、要点、关键词的结构化结果，不要编造事实，输出不超过 " + maxOutputChars + " 字。";
            // 默认重写增强：优化表达、补结构、提炼关键词
            default ->
                    "你是文档增强助手。请在不改变原始事实的前提下，优化文本表达、补充标题和层次结构、提炼关键词，输出不超过 " + maxOutputChars + " 字。";
        };

        // 用户消息
        String userMessage = "增强模式：" + mode + "\n" +
                "请处理以下文本：\n" + text;

        // 构建并返回 Prompt
        return new Prompt(
                new SystemMessage(systemMessage),
                new UserMessage(userMessage)
        );
    }

    /**
     * 安全读取配置中的 int 类型参数
     * @param settings 配置节点
     * @param key 配置key
     * @param defaultValue 默认值
     * @return 读取到的 int 值
     */
    private int readInt(JsonNode settings, String key, int defaultValue) {
        if (settings != null && settings.has(key) && settings.get(key).canConvertToInt()) {
            return settings.get(key).asInt();
        }
        return defaultValue;
    }
}