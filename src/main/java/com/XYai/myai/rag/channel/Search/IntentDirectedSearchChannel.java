package com.XYai.myai.rag.channel.Search;

import cn.hutool.core.collection.CollUtil;
import com.XYai.myai.rag.aop.Annotation.RagTraceNode;
import com.XYai.myai.rag.channel.POJO.RetrievedChunk;
import com.XYai.myai.rag.channel.POJO.SearchChannel;
import com.XYai.myai.rag.channel.POJO.SearchChannelResult;
import com.XYai.myai.rag.channel.POJO.SearchContext;
import com.XYai.myai.rag.intent.POJO.IntentNode;
import com.XYai.myai.rag.intent.POJO.SubQuestionIntent;
import io.milvus.client.MilvusClient;import jakarta.annotation.Resource;
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
    //根据节点ID在向量数据库中映射直接检索

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
        // 启用条件：有明确的意图
        return CollUtil.isNotEmpty(context.getKbIntents());
    }

    @Override
    @RagTraceNode(name = "召回", type = "search")
    public SearchChannelResult search(SearchContext context) {
        // 1.获取意图
        List<SubQuestionIntent> subQuestionIntents = context.getKbIntents();
        // 2.意图映射数据库字段

        // 2.1第一层映射分区

        // 2.2后层映射

        // 3.查找
        String expr = "";

        // 构建返回
        return null;
    }
}