package com.XYai.myai.rag.channel.Processor;

import com.XYai.myai.rag.channel.POJO.RetrievedChunk;
import com.XYai.myai.rag.channel.POJO.SearchContext;

import java.util.List;

public interface SearchResultPostProcessor {
    String getName();

    int getOrder();

    List<RetrievedChunk> process(List<RetrievedChunk> chunks, SearchContext context);
}
