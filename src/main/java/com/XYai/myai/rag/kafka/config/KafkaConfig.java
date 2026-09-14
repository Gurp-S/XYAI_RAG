package com.XYai.myai.rag.kafka.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Topic 定义 + 消费者工厂。
 *
 * Topic 分区数在一开始就设好，后续不能减少（只能增加）。
 *   etl-file:    16分区 — 最多同时处理16个文件
 *   etl-chunk:    8分区 — 最多8个chunk并行增强(LLM)
 *   trace-log:    4分区 — 链路追踪
 *   analytics-event: 4分区 — 评估+计数
 *   memory-cmd:   4分区 — 记忆持久化
 *   neo4j-cmd:    4分区 — Neo4j操作
 *   milvus-cmd:   4分区 — Milvus向量清理
 */
@Slf4j
@Configuration
public class KafkaConfig {

    @PostConstruct
    public void init() {
        log.info("KafkaConfig 已加载, bootstrap-servers=localhost:9092");
    }

    // ═══════════════════════════════════════════════
    // Topic
    // ═══════════════════════════════════════════════

    @Bean
    public NewTopic etlFile() {
        return TopicBuilder.name("etl-file").partitions(16).replicas(1).build();
    }

    @Bean
    public NewTopic etlChunk() {
        return TopicBuilder.name("etl-chunk").partitions(8).replicas(1).build();
    }

    @Bean
    public NewTopic traceLog() {
        return TopicBuilder.name("trace-log").partitions(4).replicas(1).build();
    }

    @Bean
    public NewTopic analyticsEvent() {
        return TopicBuilder.name("analytics-event").partitions(4).replicas(1).build();
    }

    @Bean
    public NewTopic memoryCmd() {
        return TopicBuilder.name("memory-cmd").partitions(4).replicas(1).build();
    }

    @Bean
    public NewTopic neo4jCmd() {
        return TopicBuilder.name("neo4j-cmd").partitions(4).replicas(1).build();
    }

    @Bean
    public NewTopic milvusCmd() {
        return TopicBuilder.name("milvus-cmd").partitions(4).replicas(1).build();
    }

    // ═══════════════════════════════════════════════
    // 生产者工厂 & KafkaTemplate（使用 JsonSerializer）
    // ═══════════════════════════════════════════════

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, 10);
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 1000);
        // 单条消息投递总时限：覆盖 broker 抖动 + 重试窗口
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
        // 元数据刷新更激进，配合 Docker 端口代理抖动场景
        props.put(ProducerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, 10000);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    /**
     * 轻量级生产者（可观测性专用）：trace-log / analytics-event 属尽力而为事件，
     * 无需 acks=all 强一致。acks=1 + 微批 + lz4 压缩，
     * 高并发检索路径每请求多个观测事件，与 ETL 强一致 producer 隔离，避免互相拖累。
     */
    @Bean
    public ProducerFactory<String, Object> fastProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 20);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 131072);
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        props.put(ProducerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, 10000);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> fastKafkaTemplate() {
        return new KafkaTemplate<>(fastProducerFactory());
    }

    // ═══════════════════════════════════════════════
    // 消费者工厂
    // ═══════════════════════════════════════════════

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // 延长超时，防止空闲断连（开发环境 Kafka 可能响应慢）
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 60000);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 60000);
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);
        // 单次 poll 拉取条数：默认 500。ETL chunk 单条处理耗时数秒（LLM 重试 + 双向量 embedding），
        // 一批处理总时长一旦超过 max.poll.interval.ms，消费者即被组踢出，整条 ETL 停摆。
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 4);
        // max.poll.interval：4 条 × 数秒/条，留足余量
        props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 900000);
        props.put(ConsumerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, 10000);
        // 信任所有包（JsonDeserializer 反序列化必需）
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /** 默认工厂（重试3次，间隔1秒） */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> defaultFactory() {
        var f = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        f.setConsumerFactory(consumerFactory());
        f.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        f.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000, 3)));
        return f;
    }

    /** 文件上传工厂（etl-file 专用，重试3次，间隔1秒） */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> fileListenerFactory() {
        var f = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        f.setConsumerFactory(consumerFactory());
        f.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        f.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000, 3)));
        return f;
    }

    /** chunk专用工厂（重试5次，间隔2秒 — LLM调用慢） */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> chunkFactory() {
        var f = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        f.setConsumerFactory(consumerFactory());
        f.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        f.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(2000, 3)));
        return f;
    }

    /** 攒批写入工厂（trace-log / analytics-event 用） */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> batchListenerFactory() {
        var f = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        f.setConsumerFactory(consumerFactory());
        f.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        f.setBatchListener(true);
        f.setConcurrency(2);
        f.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000, 3)));
        return f;
    }

    /** 轻量重试工厂（memory/neo4j/milvus 重试2次） */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> lightRetryListenerFactory() {
        var f = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        f.setConsumerFactory(consumerFactory());
        f.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        f.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000, 3)));
        return f;
    }
}
