package com.XYai.myai.RAG.Channel.Processor;

import com.XYai.myai.RAG.Channel.POJO.RetrievedChunk;
import com.XYai.myai.RAG.Channel.POJO.SearchContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class FilterPostProcessor implements SearchResultPostProcessor {

    @Override
    public String getName() {
        return "";
    }

    @Override
    public int getOrder() {
        return 5;  // 在去重之后，Rerank之前
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks, SearchContext context) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        // 1. 过滤相关性分数低于阈值的Chunk（如0.3）
//        for (RetrievedChunk chunk : chunks) {
//            Double score = chunk.getScore();
//            //TODO 配置类
//            if(score<0.3){
//                chunks.remove(chunk);
//            }
//        }
        // 2. 过滤非最新版本的文档
        // 3. 过滤权限不匹配的文档（如普通用户看不到管理员文档）
        return chunks;
    }
}