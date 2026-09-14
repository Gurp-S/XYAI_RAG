package com.XYai.myai.security.config;

/**
 * Central path policies for Spring Security. Kept as constants so CI can assert
 * admin APIs are never accidentally left public.
 */
public final class SecurityPathPolicies {

    private SecurityPathPolicies() {
    }

    /** Unauthenticated endpoints only. */
    public static final String[] PUBLIC_PATHS = {
            "/user/login",
            "/user/registry",
            "/user/reset-password",
            "/user/refresh",
            "/error",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info"
    };

    /** Admin management APIs — require ROLE_ADMIN or ROLE_ORG_ADMIN. */
    public static final String ADMIN_API_PATTERN = "/xyAdmin/**";

    /** Actuator beyond health/info — require ROLE_ADMIN. */
    public static final String ACTUATOR_PATTERN = "/actuator/**";
}
