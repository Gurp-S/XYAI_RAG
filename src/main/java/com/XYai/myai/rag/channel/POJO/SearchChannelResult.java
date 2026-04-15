package com.XYai.myai.rag.channel.POJO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 单个检索通道返回的结果封装。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchChannelResult {
	/** 通道名称 */
	private String channelName;

	/** 检索到的片段列表 */
	private List<RetrievedChunk> chunks;

	/** 任意通道级元数据 */
	private Object metadata;
}
