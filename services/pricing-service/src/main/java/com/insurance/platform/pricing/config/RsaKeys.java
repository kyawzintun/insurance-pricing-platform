package com.insurance.platform.pricing.config;

import java.security.interfaces.RSAPublicKey;
import org.springframework.core.io.Resource;
import org.springframework.security.converter.RsaKeyConverters;

/** Reads X.509 SubjectPublicKeyInfo PEM without exposing rejected key material. */
public final class RsaKeys {
    private RsaKeys() {}
    public static RSAPublicKey publicKey(Resource resource) {
        try (var input = resource.getInputStream()) {
            var key = RsaKeyConverters.x509().convert(input);
            if (key == null || key.getModulus().bitLength() < 2048) throw new IllegalArgumentException();
            return key;
        } catch (Exception ex) {
            throw new IllegalStateException("AUTH_JWT_PUBLIC_KEY must reference a valid RSA PEM key of at least 2048 bits");
        }
    }
}
