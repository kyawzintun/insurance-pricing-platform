package com.insurance.platform.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;

@Configuration(proxyBeanMethods = false)
public class JwtConfiguration {
    @Bean
    public JwtEncoder jwtEncoder(@Value("${auth.jwt.private-key}") Resource privateResource,
                                 @Value("${auth.jwt.public-key}") Resource publicResource) {
        var publicKey = RsaKeys.publicKey(publicResource);
        try (var input = privateResource.getInputStream()) {
            RSAPrivateKey privateKey = RsaKeyConverters.pkcs8().convert(input);
            if (privateKey == null || privateKey.getModulus().bitLength() < 2048
                    || !privateKey.getModulus().equals(publicKey.getModulus())) {
                throw new IllegalArgumentException();
            }
            // Also prove the configured exponents form a working pair before accepting traffic.
            var probe = Signature.getInstance("SHA256withRSA");
            byte[] message = "jwt-key-pair-check".getBytes(StandardCharsets.US_ASCII);
            probe.initSign(privateKey);
            probe.update(message);
            byte[] signature = probe.sign();
            probe.initVerify(publicKey);
            probe.update(message);

            if (!probe.verify(signature)) {
                throw new IllegalArgumentException();
            }
            var jwk = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
            return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        } catch (Exception ex) {
            throw new IllegalStateException("AUTH_JWT_PRIVATE_KEY must reference a valid PKCS#8 RSA PEM matching AUTH_JWT_PUBLIC_KEY");
        }
    }
}
