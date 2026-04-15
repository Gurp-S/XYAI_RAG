package com.XYai.myai.rag.channel.Search;

import com.XYai.myai.rag.channel.POJO.SearchChannel;
import com.XYai.myai.rag.channel.POJO.SearchChannelResult;
import com.XYai.myai.rag.channel.POJO.SearchContext;

/**
 * Elasticsearch 检索通道实现。
 * 通过全文本关键词匹配，在 ES 索引中查找相关文档片段。
 */
public class ESSearchChannel implements SearchChannel {
    @Override
    public String getName() {
        return "";
    }

    @Override
    public int getPriority() {
        return 7;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return false;
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        return null;
    }

    @Override
    public String getType() {
        return "";
    }
}
