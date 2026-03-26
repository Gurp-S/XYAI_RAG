package com.XYai.myai.Aop;

import com.XYai.myai.Service.ChatService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(RateLimitIntegrationTest.TestConfig.class)
@SuppressWarnings("unchecked")
public class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void whenUnderLimit_thenControllerReturnsOk() throws Exception {
        when(stringRedisTemplate.execute(Mockito.any(RedisScript.class), anyList(), Mockito.<Object[]>any())).thenReturn(Boolean.TRUE);

        mockMvc.perform(get("/ai/rateTest"))
                .andExpect(status().isOk())
                .andExpect(content().string("ok"));
    }

    @Test
    public void whenOverLimit_thenExceptionHandlerReturnsRateLimit() throws Exception {
        when(stringRedisTemplate.execute(Mockito.any(RedisScript.class), anyList(), Mockito.<Object[]>any())).thenReturn(Boolean.FALSE);

        mockMvc.perform(get("/ai/rateTest"))
                .andExpect(status().isOk())
                .andExpect(content().string("rateLimit"));
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        public StringRedisTemplate stringRedisTemplateMock() {
            return Mockito.mock(StringRedisTemplate.class);
        }

        @Bean
        @SuppressWarnings("unchecked")
        public RedisScript<Boolean> redisScript() {
            return (RedisScript<Boolean>) Mockito.mock(RedisScript.class);
        }

        @Bean
        public ChatService chatServiceMock() {
            return Mockito.mock(ChatService.class);
        }

        @Bean
        @Primary
        public ChatModel chatModelMock() {
            return Mockito.mock(ChatModel.class);
        }
    }
}
