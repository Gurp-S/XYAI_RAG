package com.XYai.myai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置类，提供字符串与 JSON 序列化的模板 Bean。
 */
@Configuration
public class RedisTemplateConfiguration {

	/**
	 * 创建通用 RedisTemplate。
	 *
	 * <p>该模板用于对象场景：key 使用字符串序列化，value 使用 JSON 序列化，
	 * 便于后续排查和跨语言读取。</p>
	 *
	 * @param connectionFactory Redis 连接工厂
	 * @return 可读写对象类型的 RedisTemplate
	 */
	@Bean
	public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
		RedisTemplate<String, Object> template = new RedisTemplate<>();
		template.setConnectionFactory(connectionFactory);
		StringRedisSerializer stringRedisSerializer = new StringRedisSerializer();
		GenericJackson2JsonRedisSerializer jsonRedisSerializer = new GenericJackson2JsonRedisSerializer();
		template.setKeySerializer(stringRedisSerializer);
		template.setHashKeySerializer(stringRedisSerializer);
		template.setValueSerializer(jsonRedisSerializer);
		template.setHashValueSerializer(jsonRedisSerializer);
		template.afterPropertiesSet();
		return template;
	}

	@Bean
	/**
	 * 创建字符串专用 StringRedisTemplate。
	 *
	 * @param connectionFactory Redis 连接工厂
	 * @return StringRedisTemplate 实例
	 */
	public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
		return new StringRedisTemplate(connectionFactory);
	}
}
