package com.XYai.myai.rag.rewrite;

import com.XYai.myai.rag.memory.POJO.LoadSession;
import com.XYai.myai.rag.rewrite.POJO.RewriteResult;

/**
 * 查询重写服务接口（重写与拆分）。
 * 实现应负责：接受初始的重写请求（RewriteResult），在必要时调用 LLM 进行重写与子问题拆分，
 * 返回符合 RewriteResult 结构的结果对象。
 */
public interface QueryRewriterService {

    RewriteResult callLLMRewriteAndSplit(RewriteResult userMessage, LoadSession load);

}
