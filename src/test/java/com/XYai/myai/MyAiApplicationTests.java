package com.XYai.myai;

import com.XYai.myai.Annotation.rateLimit;
import com.XYai.myai.Aop.rateLimitAspect;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.bind.annotation.RequestMapping;

@SpringBootTest
class MyAiApplicationTests {

    @Autowired
    private rateLimitAspect rateLimitAspect;

    @Test
    void contextLoads() {
    }
}
