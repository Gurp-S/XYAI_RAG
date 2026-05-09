package com.XYai.myai.rag.aop.annotation;

import java.lang.annotation.*;

/**
 * RAG 链路追踪子节点注解。
 * 用于标记 RAG 流程中的各个功能模块（如检索、重排等），以便记录其执行详情。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RagTraceNode {

    String name();

    String type();

    String taskIdArg() default "";
}
