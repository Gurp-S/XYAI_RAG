package com.XYai.myai.RAG.rewrite;

public interface QueryReweiterService{


    RewriteResult callLLMRewriteAndSplit(RewriteResult userMessage);

}
