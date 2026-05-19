package com.XYai.myai.rag.graph.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.*;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CommunitySearchResult {
    private List<Map.Entry<String, Integer>> summaries = new ArrayList<>();
    private Set<String> chunkIds = new LinkedHashSet<>();

    public void addCommunitySummary(String summary, int matchCount) {
        summaries.add(Map.entry(summary, matchCount));
    }

    public void addAllChunkIds(Collection<String> ids) {
        chunkIds.addAll(ids);
    }
}