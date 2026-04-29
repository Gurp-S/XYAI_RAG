package com.XYai.myai.rag.channel.Search;

import com.XYai.myai.rag.channel.POJO.RetrievedChunk;
import com.XYai.myai.rag.channel.POJO.SearchChannel;
import com.XYai.myai.rag.channel.POJO.SearchChannelResult;
import com.XYai.myai.rag.channel.POJO.SearchContext;
import com.XYai.myai.rag.intent.POJO.IntentNode;
import com.XYai.myai.rag.intent.POJO.SubQuestionIntent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Component
public class MCPToolSearchChannel implements SearchChannel {


    @Resource(name = "searchChannelExecutor")
    private ThreadPoolTaskExecutor searchChannelExecutor;

    @Override
    public String getName() {
        return "mcp-tool-channel";
    }

    @Override
    public int getPriority() {
        return 5; // 低于 KB 意图检索优先级，根据需要调整
    }

    @Override
    public String getType() {
        return "mcp-tool";
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        // 意图节点中有指定的mcp工具ID
        List<SubQuestionIntent> kbIntents = context.getKbIntents();
        Set<String> mcpToolIds = kbIntents.stream()
                .map(SubQuestionIntent::getSubIntent)
                .flatMap(Collection::stream)
                .map(IntentNode::getMcpToolId)
                .collect(Collectors.toSet());
        return mcpToolIds.isEmpty();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        List<SubQuestionIntent> kbIntents = context.getKbIntents();
        Set<String> mcpToolIds = kbIntents.stream()
                .map(SubQuestionIntent::getSubIntent)
                .flatMap(Collection::stream)
                .map(IntentNode::getMcpToolId)
                .collect(Collectors.toSet());

        List<CompletableFuture<List<RetrievedChunk>>> MCPToolResult = mcpToolIds.stream()
                .map(mcpToolId -> CompletableFuture.supplyAsync(
                        () -> getMCPToolResult(mcpToolId),
                        searchChannelExecutor
                ))
                .toList();

        // 合并结果 → 排序 → 截断
        List<RetrievedChunk> mcpSearchResult = MCPToolResult.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .toList();

        return SearchChannelResult.builder()
                .channelName(getName())
                .chunks(mcpSearchResult)
                .metadata(mcpSearchResult.stream().map(RetrievedChunk::getMetadata).toList())
                .build();
    }

    private List<RetrievedChunk> getMCPToolResult(String mcpToolId) {
        return null;
    }
}
