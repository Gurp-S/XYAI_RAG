package com.XYai.myai.rag.channel.Processor;

import com.XYai.myai.rag.channel.POJO.RetrievedChunk;
import com.XYai.myai.rag.channel.POJO.SearchContext;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 检索结果重排序 (Rerank) 后处理器。
 * 对多个检索通道返回的初步结果进行二次打分和排序，以提高搜索结果的准确度。
 */
@Component
public class RerankPostProcessor implements SearchResultPostProcessor {

    // Rerank模型导入

    @Override
    public String getName() {
        return "";
    }

    @Override
    public int getOrder() {
        return 10; // 最后执行，排序后直接输出结果
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks, SearchContext context) {
        //mb25+embedding

        // 调用Rerank模型，重新排序
        return chunks;
    }
}