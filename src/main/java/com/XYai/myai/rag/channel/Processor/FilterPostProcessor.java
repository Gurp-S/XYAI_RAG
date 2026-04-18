package com.XYai.myai.rag.channel.Processor;

import com.XYai.myai.rag.channel.POJO.RetrievedChunk;
import com.XYai.myai.rag.channel.POJO.SearchContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 过滤后处理器。
 *
 * <p>定位：召回后处理中间阶段（去重之后、重排之前），负责做“硬规则”剔除。</p>
 * <p>目标：把明显不合格候选提前过滤，避免污染 Rerank 输入。</p>
 */
@Slf4j
@Component
public class FilterPostProcessor implements SearchResultPostProcessor {

    private static final String NAME = "filter-processor";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return 5;  // 在去重之后，Rerank之前
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks, SearchContext context) {
        // Step 0. 输入说明
        // - chunks: 已经做过去重的候选集合
        // - context: query + 意图 + 用户侧元信息（metadata）

        // Step 1. 空输入短路
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        // Step 2. 明确阶段职责
        // 召回 -> 去重 -> 过滤(当前类) -> 重排 -> TopK截断
        // 当前阶段只做“剔除”，不做“排序”。

        // Step 3. 相关性阈值过滤
        // TODO:
        // 1) 从配置读取 minScore（默认 0.3）
        // 2) 遍历 chunks，丢弃 score == null 或 score < minScore 的项
        // 3) 对被过滤原因打 debug 日志（便于排障）
        for (RetrievedChunk chunk : chunks) {
            Double score = chunk.getScore();
            if(score<0.3){
                log.info("文本相似度较低,文本ID:{}",chunk.getId());
                chunks.remove(chunk);
            }
        }

        // Step 4. 版本过滤
        // TODO:
        // 1) 读取 metadata.docId / metadata.version / metadata.updatedAt
        // 2) 同 docId 只保留版本最高或 updatedAt 最新的一条
        // 3) 兼容缺失字段: 缺版本信息的文档可先保留并降权（或直接剔除，按业务决定）
        for (RetrievedChunk chunk : chunks) {
            Object docId = chunk.getMetadata().get(chunk.getId());
            Object version = chunk.getMetadata().get("version");
            Object updatedAt = chunk.getMetadata().get("updatedAt");
        }
        // Step 5. 权限过滤（重点）
        // TODO:
        // 1) 从 context.metadata 读取 userId/role/groupId
        // 2) 从 chunk.metadata 读取 ownerId/sharedWith/visibility/groupId
        // 3) 判定规则示例：
        //    - ownerId == userId => 可见
        //    - sharedWith 包含 userId => 可见
        //    - visibility == public => 可见
        //    - visibility == group 且 groupId 相同 => 可见
        // 4) 不可见即剔除，避免越权数据进入 Rerank 与最终回答

        // Step 6. 数据完整性过滤
        // TODO:
        // 1) content 为空 -> 剔除
        // 2) id 为空 -> 剔除（或生成临时 id 后继续）
        // 3) metadata 结构异常 -> 剔除并打日志
        // 4) 可选: 对过长 content 截断，避免 Rerank token 超限

        // Step 7. 输出
        // 返回“清洗后候选集”，交给 RerankPostProcessor 做最终排序。
        return chunks;
    }
}