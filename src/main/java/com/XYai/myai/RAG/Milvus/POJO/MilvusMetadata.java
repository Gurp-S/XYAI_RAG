package com.XYai.myai.RAG.Milvus.POJO;

import java.util.Set;

/**
 * 存入metadata的数据
 */
public class MilvusMetadata {

    public static Set<String> metadata = Set.of(
            "fileName",
            "mimeType",
            "chunkId",
            "chunkSize",
            "kbId",
            "createTime"
    );
}
