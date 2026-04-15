package com.XYai.myai.rag.channel.POJO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 检索到的文档片段（chunk）的描述信息，供通道之间和后处理器传递。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievedChunk {
	/** 片段唯一 id（可为文档 id + offset） */
	private String id;

	/** 来源集合/collection 名称（用于标记来源） */
	private String collectionName;

	/** 片段文本内容 */
	private String content;

	/** 检索得分（越高越相关） */
	private Double score;

	/** 可选的元数据（例如文档标题、url、sourceId 等） */
	private Map<String, Object> metadata;
}
