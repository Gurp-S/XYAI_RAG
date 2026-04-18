package com.XYai.myai.rag.channel.Processor;

import com.XYai.myai.rag.channel.POJO.RetrievedChunk;
import com.XYai.myai.rag.channel.POJO.SearchContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 去重后处理器。
 *
 * <p>定位：召回后处理第一步，优先移除重复 chunk，降低后续过滤与重排成本。</p>
 */
@Component
public class DeduplicationPostProcessor implements SearchResultPostProcessor {

    private static final String NAME = "deduplication-processor";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return 1;  // 第一个执行，先去重再进行后续处理
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks, SearchContext context) {
        // Step 0. 空输入保护：避免 NPE，让后续处理器拿到稳定空列表。
        if (chunks == null || chunks.isEmpty()) {
            return chunks;
        }

        // Step 1. 选择去重键（当前使用 id）。
        // TODO(可选): 若 id 为空，可退化到 collectionName+content 哈希作为去重键。
        for (RetrievedChunk chunk : chunks) {
            String chunkId = chunk.getId();
        }
        // Step 2. 执行去重。
        // - key: RetrievedChunk::getId
        // - value: 当前 chunk
        // - 冲突策略: 同 key 出现多次时保留 first，丢弃 later
        // 说明: “保留 first”通常意味着优先保留较早进入合并列表的通道结果。

        // Step 3. 转回 List 继续传递给后续 Processor。

        // Step 4(后续扩展): 若要做“语义去重”，可以在此接入 embedding 相似度去重。
        // 示例: deduplicateByContent(chunks, similarityThreshold=0.8)
        // 注意: 语义去重成本高，建议在 TopN 候选上执行。
        return null;
    }
}