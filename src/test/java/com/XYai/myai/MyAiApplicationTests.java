package com.XYai.myai;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Full-context smoke test — requires local infra. Disabled in CI gate.
 * Use focused unit tests under security/rag/exception packages for PR checks.
 */
@SpringBootTest
@Disabled("Requires local MySQL/Redis/Milvus; enable for manual smoke only")
class MyAiApplicationTests {

    @Test
    void contextLoads() {
    }
}
