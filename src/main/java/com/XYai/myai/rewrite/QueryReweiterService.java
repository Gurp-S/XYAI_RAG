package com.XYai.myai.rewrite;

public interface QueryReweiterService{


    RewriteResult callLLMRewriteAndSplit(RewriteResult userMessage);

}
