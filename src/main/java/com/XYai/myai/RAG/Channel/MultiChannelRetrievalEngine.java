package com.XYai.myai.RAG.Channel;

import com.XYai.myai.RAG.Channel.POJO.RetrievedChunk;
import com.XYai.myai.RAG.Channel.POJO.SearchChannel;
import com.XYai.myai.RAG.Channel.POJO.SearchChannelResult;
import com.XYai.myai.RAG.Channel.POJO.SearchContext;
import com.XYai.myai.RAG.Channel.Processor.DeduplicationPostProcessor;
import com.XYai.myai.RAG.Channel.Processor.FilterPostProcessor;
import com.XYai.myai.RAG.Channel.Processor.RerankPostProcessor;
import com.XYai.myai.RAG.Channel.Processor.SearchResultPostProcessor;
import com.XYai.myai.RAG.intent.POJO.SubQuestionIntent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 多路召回检索引擎。
 * 负责协调不同的检索通道（如向量检索、全文检索等）进行并行检索，并对结果进行去重、过滤和重排等后处理。
 */
@Slf4j
@Component
public class MultiChannelRetrievalEngine {

	@Resource
	private List<SearchChannel> channels;

	@Resource
	private List<SearchResultPostProcessor> postProcessors;
	@Resource
	private DeduplicationPostProcessor deduplicationPostProcessor;
	@Resource
	private FilterPostProcessor filterPostProcessor;
	@Resource
	private RerankPostProcessor rerankPostProcessor;

	/**
	 * 多通道检索主入口：筛选启用通道 -> 并行检索 -> 合并 -> 后处理。
	 */
	public List<RetrievedChunk> retrieve(List<SubQuestionIntent> questionIntents, String query) {
		// 构建查找对象
		SearchContext context = new SearchContext();
		context.setKbIntents(questionIntents);
		context.setQuestion(query);
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
				.map(channel -> CompletableFuture.supplyAsync(() -> safeSearch(channel, context)))
				.toList();
		// 没有检索到文章
		if (futures.isEmpty())
			return null;
		// 查找结果合并
		List<RetrievedChunk> merged = futures.stream()
				.map(CompletableFuture::join)
				.filter(Objects::nonNull)
				.flatMap(res -> res.getChunks() == null ? List.<RetrievedChunk>of().stream() : res.getChunks().stream())
				.toList();

		// 进行后处理返回chunks
		return applyPostProcessors(merged, context);
	}

	private SearchChannelResult safeSearch(SearchChannel channel, SearchContext context) {
		try {
			return channel.search(context);
		} catch (Exception e) {
			// 记录异常并返回空的 SearchChannelResult，避免静默失败
			log.error("Search channel '{}' failed for question '{}': {}", channel == null ? "null" : channel.getName(),
					context == null ? "null" : context.getQuestion(), e.getMessage(), e);
			return SearchChannelResult.builder()
					.channelName(channel == null ? "unknown" : channel.getName())
					.chunks(List.of())
					.build();
		}
	}

	private List<RetrievedChunk> applyPostProcessors(List<RetrievedChunk> merged, SearchContext context) {
		deduplicationPostProcessor.process(merged, context);
		filterPostProcessor.process(merged, context);
		rerankPostProcessor.process(merged, context);
		return merged;
	}
}
