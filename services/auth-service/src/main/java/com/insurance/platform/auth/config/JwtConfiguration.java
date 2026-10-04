package com.insurance.platform.auth.config;

import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
public class JwtConfiguration {
    @Bean
    public JwtEncoder jwtEncoder(@Value("${auth.jwt.secret}") String encodedSecret) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("AUTH_JWT_SECRET must be valid Base64 (at least 32 random bytes)");
        }
        if (key.length < 32) {
            throw new IllegalStateException("AUTH_JWT_SECRET must encode at least 32 random bytes");
        }
        return NimbusJwtEncoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256")).build();
    }
}
