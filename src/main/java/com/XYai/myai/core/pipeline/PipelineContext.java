package com.XYai.myai.core.pipeline;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流水线上下文容器。
 *
 * <p>用于在不同节点之间共享中间结果与运行参数。</p>
 */
public class PipelineContext {
    private final Map<String, Object> props = new ConcurrentHashMap<>();

    /**
     * 返回上下文底层属性映射。
     *
     * @return 可变属性 Map
     */
    public Map<String, Object> props() { return props; }

    /**
     * 按 key 获取指定类型的上下文值。
     *
     * @param key 属性键
     * @param type 目标类型
     * @param <T> 泛型类型
     * @return 类型转换后的值
     */
    public <T> T get(String key, Class<T> type) { return type.cast(props.get(key)); }

    /**
     * 写入或覆盖上下文属性。
     *
     * @param key 属性键
     * @param value 属性值
     */
    public void set(String key, Object value) { props.put(key, value); }
}

