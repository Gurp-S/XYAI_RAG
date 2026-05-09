package com.XYai.myai.rag.aop.annotation;

import java.lang.annotation.*;

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

    /**
     * 任务ID在方法参数中的来源路径，支持 context.taskId 这类写法。
     */
    String taskIdArg();
}
