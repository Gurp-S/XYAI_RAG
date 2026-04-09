package com.XYai.myai.RAG.Aop.Annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * RAG 链路追踪根入口注解。
 * 用于标记 RAG 请求的初始处理入口，启动整个链路追踪记录。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RagTraceRoot {

    String name();

    String conversationIdArg();

    String taskIdArg();
}
