package com.XYai.myai.RAG.Channel.Processor;

import com.XYai.myai.RAG.Channel.POJO.RetrievedChunk;
import com.XYai.myai.RAG.Channel.POJO.SearchContext;

import java.util.List;

public interface SearchResultPostProcessor {
    String getName();

    int getOrder();

    List<RetrievedChunk> process(List<RetrievedChunk> chunks, SearchContext context);
}
