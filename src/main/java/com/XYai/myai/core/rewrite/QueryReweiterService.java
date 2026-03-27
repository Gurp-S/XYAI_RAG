package com.XYai.myai.core.rewrite;

import com.XYai.myai.core.dto.RewriteResult;

public interface QueryReweiterService{


    RewriteResult callLLMRewriteAndSplit(String userQuestion, String context);

}
