package com.XYai.myai.rag.milvus.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCollectionCache {
    private volatile List<String> cachedCollectionNames = Collections.emptyList();
    private Long expireTime;
}
