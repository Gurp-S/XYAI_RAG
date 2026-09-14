package com.XYai.myai.security.config;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityPathPoliciesTest {

    @Test
    void adminApiMustNotBePublic() {
        List<String> publicPaths = Arrays.asList(SecurityPathPolicies.PUBLIC_PATHS);
        assertFalse(publicPaths.stream().anyMatch(p -> p.contains("xyAdmin")),
                "/xyAdmin/** must not be permitAll");
        assertTrue(SecurityPathPolicies.ADMIN_API_PATTERN.equals("/xyAdmin/**"));
    }

    @Test
    void healthEndpointIsPublic() {
        List<String> publicPaths = Arrays.asList(SecurityPathPolicies.PUBLIC_PATHS);
        assertTrue(publicPaths.contains("/actuator/health"));
    }
}
