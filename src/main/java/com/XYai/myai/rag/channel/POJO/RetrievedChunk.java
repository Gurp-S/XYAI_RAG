package com.XYai.myai.rag.channel.POJO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/*** 检索到的文档片段（chunk）传输对象。
 *
 * <p>在召回通道与 SearchResultPostProcessor 链路间流转，承载：</p>
 * <p>1) 基础文本内容；2) 初始检索分数；3) 权限/版本等过滤所需元数据。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievedChunk {
	/** 片段唯一 id（建议稳定且可复现，如 docId + chunkIndex）。去重阶段依赖该字段。 */
	private String id;

	/** 来源集合/collection 名称（用于来源追踪、通道分析、问题排障）。 */
	private String collectionName;

	/** 片段文本内容（Rerank 与最终回答拼装的核心输入）。 */
	private String content;

	/** 初始检索得分（越高越相关，可能来自 BM25/向量检索/融合策略）。 */
	private Double score;

	/** 元数据（权限、版本、时间戳、来源标识等；过滤阶段通常基于该字段做规则判断）。 */
	private Map<String, Object> metadata;
}
