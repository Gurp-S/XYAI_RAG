package com.XYai.myai.exception;

import com.XYai.myai.config.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExceptionControllerTest {

    @Test
    void rateLimitReturnsUnifiedResult() {
        ExceptionController controller = new ExceptionController();
        Result<Void> result = controller.rateLimitExceptionHandle(new RateLimitException());
        assertEquals(429, result.getCode());
        assertEquals("rate limit exceeded", result.getMsg());
    }
}
