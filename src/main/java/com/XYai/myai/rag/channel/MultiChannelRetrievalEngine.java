package com.XYai.myai.rag.channel;

import com.XYai.myai.rag.channel.POJO.RetrievedChunk;
import com.XYai.myai.rag.channel.POJO.SearchChannel;
import com.XYai.myai.rag.channel.POJO.SearchChannelResult;
import com.XYai.myai.rag.channel.POJO.SearchContext;
import com.XYai.myai.rag.channel.Processor.DeduplicationPostProcessor;
import com.XYai.myai.rag.channel.Processor.FilterPostProcessor;
import com.XYai.myai.rag.channel.Processor.RerankPostProcessor;
import com.XYai.myai.rag.channel.Processor.SearchResultPostProcessor;
import com.XYai.myai.rag.intent.POJO.SubQuestionIntent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

	@Resource(name = "searchChannelExecutor")
	private ThreadPoolTaskExecutor searchChannelExecutor;

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
