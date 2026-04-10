package com.XYai.myai.RAG.Aop.Annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TrackETLJob {
    // 可以扩展，比如指定任务类型
}