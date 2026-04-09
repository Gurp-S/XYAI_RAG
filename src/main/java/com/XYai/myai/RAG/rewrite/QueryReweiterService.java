package com.XYai.myai.RAG.rewrite;

import com.XYai.myai.RAG.Memory.POJO.LoadSession;
import com.XYai.myai.RAG.rewrite.POJO.RewriteResult;

public interface QueryReweiterService{


    RewriteResult callLLMRewriteAndSplit(RewriteResult userMessage, LoadSession load);

}
