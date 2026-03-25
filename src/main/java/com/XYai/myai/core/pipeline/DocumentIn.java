package com.XYai.myai.core.pipeline;

import java.util.Map;

/**
 * 流水线输入文档实体。
 */
public class DocumentIn {
    private final String id;
    private final byte[] content;
    private final Map<String, Object> metadata;

    /**
     * 创建输入文档。
     *
     * @param id 文档唯一标识
     * @param content 文档二进制内容
     * @param metadata 文档元数据
     */
    public DocumentIn(String id, byte[] content, Map<String, Object> metadata) {
        this.id = id;
        this.content = content;
        this.metadata = metadata;
    }

    /**
     * @return 文档 ID
     */
    public String getId() { return id; }

    /**
     * @return 文档内容
     */
    public byte[] getContent() { return content; }

    /**
     * @return 文档元数据
     */
    public Map<String, Object> getMetadata() { return metadata; }
}

