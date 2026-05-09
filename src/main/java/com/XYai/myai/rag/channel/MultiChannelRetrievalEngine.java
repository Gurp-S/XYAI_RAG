package com.XYai.myai.rag.channel;

import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.channel.pojo.SearchChannel;
import com.XYai.myai.rag.channel.pojo.SearchChannelResult;
import com.XYai.myai.rag.channel.pojo.SearchContext;
import com.XYai.myai.rag.channel.processor.*;
import com.XYai.myai.rag.intent.pojo.SubQuestionIntent;
import com.XYai.myai.rag.rewrite.pojo.RewriteResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * 多路召回检索引擎。
 * 负责协调不同的检索通道（如向量检索、全文检索等）进行并行检索，
 * 并在召回合并后执行固定顺序的后处理：去重 -> 过滤 -> 重排。
 */
@Slf4j
@Service
public class MultiChannelRetrievalEngine {

    @Resource
    private List<SearchChannel> channels;
    @Resource
    private BM25PostProcessor bm25PostProcessor;
    @Resource
    private FilterPostProcessor filterPostProcessor;
    @Resource
    private RerankPostProcessor rerankPostProcessor;

    @Resource(name = "searchChannelExecutor")
    private ThreadPoolTaskExecutor searchChannelExecutor;

    /**
     * 多通道检索主入口：筛选启用通道 -> 并行检索 -> 合并 -> 后处理。
     *
     * <p>链路说明：</p>
     * <p>1) 根据 SearchContext 过滤出可用通道，并按 priority 升序执行。</p>
     * <p>2) 每个通道并行执行，单通道异常/超时仅影响自身，不中断全局召回。</p>
     * <p>3) 合并所有通道的 chunks 后，进入 SearchResultPostProcessor 链路做质量提升。</p>
     * <p>4) 当前后处理采用固定顺序调用（非动态排序）：Deduplication -> Filter -> Rerank。</p>
     */
    public List<RetrievedChunk> retrieve(List<SubQuestionIntent> questionIntents, RewriteResult query, String conversationId,String originalQuery) {
        // 构建查找对象
        SearchContext context = new SearchContext();
        context.setOriginalQuery(originalQuery);
        context.setKbIntents(questionIntents);
        context.setRewriteQuestion(query);
        context.setConversationId(conversationId);
        // 进行可执行的通道查找
        List<SearchChannel> enabledChannels = channels.stream()
                .filter(channel -> channel != null && channel.isEnabled(context))
                .sorted(Comparator.comparingInt(SearchChannel::getPriority))
                .toList();
        // 没有可以检索的通道
        if (enabledChannels.isEmpty())
            return null;
        // 并行查找
        List<CompletableFuture<SearchChannelResult>> futures = enabledChannels.stream()
                .map(channel -> CompletableFuture.supplyAsync(() -> safeSearch(channel, context), searchChannelExecutor)
                        // 超时时间：10 秒
                        .orTimeout(10, TimeUnit.SECONDS)
                        // 超时/异常都返回空结果，不中断整体流程
                        .exceptionally(ex -> {
                            String channelName = channel == null ? "unknown" : channel.getName();
                            if (ex instanceof TimeoutException) {
                                log.warn("检索通道[{}]执行超时", channelName);
                            } else {
                                log.error("检索通道[{}]执行异常", channelName, ex);
                            }
                            return SearchChannelResult.builder()
                                    .channelName(channelName)
                                    .chunks(List.of())
                                    .build();
                        })
                )
                .toList();
        if (futures.isEmpty())
            return null;
        // 查找结果合并
        List<RetrievedChunk> merged = futures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .flatMap(res -> res.getChunks() == null ? Stream.of() : res.getChunks().stream())
                .toList();

        // 进行后处理返回chunks
        return applyPostProcessors(merged, context);
    }

    /**
     * 通道执行保护层：把单通道错误收敛为空结果，避免拖垮整条召回链路。
     */
    private SearchChannelResult safeSearch(SearchChannel channel, SearchContext context) {
        try {
            return channel.search(context);
        } catch (Exception e) {
            // 记录异常
            log.error("查询失败 '{}' 失败问题 '{}': {}", channel == null ? "null" : channel.getName(),
                    context == null ? "null" : context.getRewriteQuestion(), e.getMessage(), e);
            return SearchChannelResult.builder()
                    .channelName(channel == null ? "unknown" : channel.getName())
                    .chunks(List.of())
                    .build();
        }
    }

    /**
     * 后处理编排入口。
     *
     * <p>职责边界：</p>
     * <p>- 去重：去掉重复 chunk，减少后续处理成本。</p>
     * <p>- 过滤：剔除不满足质量/权限/版本约束的候选。</p>
     * <p>- 重排：对保留候选重新打分排序，提升最终相关性。</p>
     */
    private List<RetrievedChunk> applyPostProcessors(List<RetrievedChunk> merged, SearchContext context) {
        // 1 bm25打分
        List<RetrievedChunk> bm25Process = bm25PostProcessor.process(merged, context);//不同的collection重复文档,相似文档
        log.info("bm25:{}",bm25Process.stream().map(RetrievedChunk::getBm25Score).toList());
        // 2 过滤：在重排前先做硬约束清洗（权限、版本、低质量等）。
        List<RetrievedChunk> filterProcess = filterPostProcessor.process(bm25Process, context);//分数低,版本低,权限不足
        log.info("filter:{}",filterProcess.stream().map(RetrievedChunk::getScore).toList());
        // 3 重排：基于语义模型或融合策略调整最终排序。
        //return rerankPostProcessor.process(filterProcess, context);//rerank模型
        return filterProcess;
    }
}
