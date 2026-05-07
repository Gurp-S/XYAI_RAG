package com.XYai.myai.rag.rewrite;

import com.XYai.myai.rag.memory.POJO.LoadSession;
import com.XYai.myai.rag.rewrite.POJO.RewriteResult;
import com.XYai.myai.rag.rewrite.POJO.RewriterProperties;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.RestController;

/**
 * 查询重写接口。
 *
 * <p>将用户原始提问转换为更适合检索的标准查询。</p>
 */
@RestController
public class QueryRewriter {

    @Resource
    private QueryRewriterService queryRewriterService;
    @Resource
    private ChatModel chatModel;
    @Resource
    private RewriterProperties rewriterProperties;

    /**
     * 重写查询文本。
     *
     * @param userQuestion 原始查询
     * @return 重写后的查询
     */
    public RewriteResult rewrite(String userQuestion, LoadSession load) {
        //TODO如果不是问题会导致ai忽略系统提示词

        // 简单规则重写
//        queryRewriterService.easyReweite(userMessage, load);
        // 判断是否开启llm
        RewriteResult userMessage = RewriteResult.builder().rewrittenQuery(userQuestion).build();
        if (!rewriterProperties.getRewriterEnabled() || userQuestion.length() < 15) return userMessage;
        // 使用 LLM 进行智能重写
        return queryRewriterService.callLLMRewriteAndSplit(userMessage, load);
    }




    /*分次重写
    private String callLLMRewriteAndSplit(String normalizedQuestion, String userQuestion, String context) {
        // 将所有指令组合成一个没有对话感的工作任务

        String promptText = """
        你现在是一个“智能重写专家”，不要回答问题，仅仅执行重写任务。
        任务：结合上下文(如果有)，将用户输入的内容提取并重写为最适合向量检索的核心关键词语句。只输出改写后的结果，禁止输出任何其他内容。
        【处理规则】
        1. 意图区分：首先判断用户的输入是“知识检索提问”还是“陈述/闲聊/指令”。
        2. 若为提问（如“怎么用”、“为什么”）：结合上下文，将其重写为最适合向量数据库检索的清晰问题，补全缺失的指代和主谓宾。
        3. 若为陈述/闲聊/指令（如“你好，你是我编写的RAG引擎”、“帮我写一首诗”）：**严禁强行提取检索关键词！**，只需剔除“哎”、“请问”等无意义语气词，必须完整保留用户的原意、指令要求和原句结构。
        4. 红线警告：无论用户说什么，你都绝对不能回答用户的问题，也不能输出“好的”、“改写如下”等任何多余的解释文字。
        【处理示例】
        上下文：无
        输入：<<< 你好，你是我编写的RAG智能体引擎，用于公司检索回答问题 >>>
        输出：你是我编写的RAG智能体引擎，用于公司检索回答问题
        上下文：上文讨论了“Spring Boot”
        输入：<<< 那个啥，它咋处理跨域啊？ >>>
        输出：Spring Boot如何处理跨域请求？
        【当前任务】
        上下文：%s
        输入：<<< %s >>>
        """.formatted(
                context == null || context.isBlank() ? "无" : context.trim(),
                userQuestion.trim()
        );
        Prompt prompt = new Prompt(
            new UserMessage(promptText)
        );
        return chatModel.call(prompt).getResult().getOutput().getText();
    }*/
}

