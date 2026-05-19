package com.XYai.myai.rag.graph.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.Set;

@Data@Builder@AllArgsConstructor@NoArgsConstructor
public class GraphResult {

    Set<String> fileChunkIds;

    Map<String, Integer> chunkMatchCount;
}
