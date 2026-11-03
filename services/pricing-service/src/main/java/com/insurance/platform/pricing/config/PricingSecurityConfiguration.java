package com.insurance.platform.pricing.config;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/** Admin JWT authorization is independent of Gateway. Internal calculation requires a distinct service identity. */
@Configuration(proxyBeanMethods = false)
public class PricingSecurityConfiguration {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        var authentication = new JwtAuthenticationConverter();
        authentication.setJwtGrantedAuthoritiesConverter(jwt -> "service".equals(jwt.getClaimAsString("token_use"))
                ? List.of() : authorities.convert(jwt));
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/api/v1/admin/pricing/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/internal/v1/pricing/calculate").access((auth, context) -> {
                            var principal = auth.get().getPrincipal();
                            boolean allowed = principal instanceof Jwt jwt
                                    && "service".equals(jwt.getClaimAsString("token_use"))
                                    && "quote-service".equals(jwt.getSubject())
                                    && jwt.getAudience().contains("pricing-service")
                                    && jwt.getClaims().get("scope") instanceof Collection<?> scopes
                                    && scopes.contains("pricing:calculate");
                            return new AuthorizationDecision(allowed);
                        })
                        .requestMatchers(HttpMethod.GET, "/actuator/metrics", "/actuator/metrics/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").denyAll()
                        .anyRequest().denyAll())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, ex) -> unauthorized(response))
                        .accessDeniedHandler((request, response, ex) -> forbidden(response)))
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authentication))
                        .authenticationEntryPoint((request, response, ex) -> unauthorized(response))
                        .accessDeniedHandler((request, response, ex) -> forbidden(response)))
                .build();
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        error(response, 401, "UNAUTHORIZED", "Authentication is required");
    }
    private static void forbidden(HttpServletResponse response) throws IOException {
        error(response, 403, "FORBIDDEN", "Access denied");
    }
    private static void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
