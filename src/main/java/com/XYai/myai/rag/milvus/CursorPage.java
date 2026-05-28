package com.XYai.myai.rag.milvus;

import lombok.Data;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

@Data
public class CursorPage<T> {
    private List<T> items;
    private String nextCursor;
    private boolean hasMore;

    public CursorPage() {
        this.items = Collections.emptyList();
        this.nextCursor = null;
        this.hasMore = false;
    }

    public CursorPage(List<T> items, String nextCursor, boolean hasMore) {
        this.items = items != null ? items : Collections.emptyList();
        this.nextCursor = nextCursor;
        this.hasMore = hasMore;
    }

}
