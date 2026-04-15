package com.XYai.myai.rag.channel.Processor;

import com.XYai.myai.rag.channel.POJO.RetrievedChunk;
import com.XYai.myai.rag.channel.POJO.SearchContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class DeduplicationPostProcessor implements SearchResultPostProcessor {

    @Override
    public String getName() {
        return "deduplication-processor";
    }

    @Override
    public int getOrder() {
        return 1;  // 第一个执行，先去重再进行后续处理
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks, SearchContext context) {
        // 方式1：根据Chunk的唯一标识去重（简单高效）
        return new ArrayList<>(chunks.stream()
                .collect(Collectors.toMap(
                        RetrievedChunk::getId,  // 唯一标识
                        Function.identity(),
                        (existing, replacement) -> existing  // 重复时保留第一个
                ))
                .values());

        // 方式2：根据内容相似度去重（更精准，性能稍低）
        // return deduplicationService.deduplicateByContent(chunks, 0.8);
    }
}