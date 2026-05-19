package com.XYai.myai.security.config;

import com.XYai.myai.security.filter.LoginUserInfoFilter;
import com.XYai.myai.security.handler.CustomAccessDeniedHandler;
import com.XYai.myai.security.handler.CustomAuthenticationEntryPoint;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

import java.util.Set;

@Configuration
@Slf4j
public class SecurityConfig {

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/user/login", "/user/registry", "/user/reset-password", "/user/refresh",
            "/static", "/public", "/error","/xyAdmin/**");

    private final JwtDecoder jwtDecoder;

    public SecurityConfig(JwtDecoder jwtDecoder) {
        this.jwtDecoder = jwtDecoder;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        CustomAuthenticationEntryPoint entryPoint = new CustomAuthenticationEntryPoint();

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(publicPathAwareBearerTokenResolver())
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(entryPoint))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS.toArray(new String[0]))
                        .permitAll()
                        .anyRequest().authenticated())
                .addFilterAfter(loginUserInfoFilter(), BearerTokenAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(new CustomAccessDeniedHandler()));

        return http.build();
    }

    private BearerTokenResolver publicPathAwareBearerTokenResolver() {
        DefaultBearerTokenResolver defaultResolver = new DefaultBearerTokenResolver();
        return (HttpServletRequest request) -> {
            String path = request.getRequestURI();
            for (String pub : PUBLIC_PATHS) {
                if (path.startsWith(pub)) {
                    return null;
                }
            }
            return defaultResolver.resolve(request);
        };
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        grantedAuthoritiesConverter.setAuthoritiesClaimName("roles");
        grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);
        return converter;
    }

    @Bean
    public LoginUserInfoFilter loginUserInfoFilter() {
        return new LoginUserInfoFilter();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }
}
