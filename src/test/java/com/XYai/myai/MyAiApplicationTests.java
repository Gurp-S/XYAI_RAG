package com.XYai.myai;

import com.XYai.myai.RAG.Aop.rateLimitAspect;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class MyAiApplicationTests {

    @Autowired
    private rateLimitAspect rateLimitAspect;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void contextLoads() {
        stringRedisTemplate.opsForHash().put("intent:tree:","根节点","root");
        stringRedisTemplate.opsForHash().put("intent:tree:","提问","root-query");
        stringRedisTemplate.opsForHash().put("intent:tree:","公司","root-query-company");
        stringRedisTemplate.opsForHash().put("intent:tree:","人事","root-query--companyPersonnel");
        stringRedisTemplate.opsForHash().put("intent:tree:","请假","root-query-company-Personnel-leave");
        stringRedisTemplate.opsForHash().put("intent:tree:","闲聊","root-chat");
        stringRedisTemplate.opsForHash().put("intent:tree:","用户","root-chat-user");
    }
}
