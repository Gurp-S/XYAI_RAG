package com.XYai.myai.RAG.ETLpipeline.Upload;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the text splitter.
 */
@Component
@ConfigurationProperties(prefix = "splitter")
public class SplitterProperties {
    /** chunk size in characters (default) */
    private int chunkSize = 1200;
    /** overlap in characters */
    private int overlap = 200;
    /** when true, for Chinese prefer token-based chunking */
    private boolean chineseTokenize = true;

    public int getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(int chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getOverlap() {
        return overlap;
    }

    public void setOverlap(int overlap) {
        this.overlap = overlap;
    }

    public boolean isChineseTokenize() {
        return chineseTokenize;
    }

    public void setChineseTokenize(boolean chineseTokenize) {
        this.chineseTokenize = chineseTokenize;
    }
}

