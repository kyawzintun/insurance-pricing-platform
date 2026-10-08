package com.insurance.platform.pricing;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class AdminJwtSupport {
    private static final byte[] KEY = new byte[32];
    static { new SecureRandom().nextBytes(KEY); }
    static String secret() { return Base64.getEncoder().encodeToString(KEY); }
    static String token(String role) throws Exception {
        return token(role, "insurance-auth-service", Instant.now().getEpochSecond() + 300);
    }
    static String token(String role, String issuer, long expiry) throws Exception {
        return sign("{\"sub\":\"00000000-0000-0000-0000-000000000001\",\"roles\":[\"" + role
                + "\"],\"iss\":\"" + issuer + "\",\"iat\":" + (Instant.now().getEpochSecond() - 60)
                + ",\"exp\":" + expiry + "}");
    }
    static String sign(String claims) throws Exception {
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String data = encoder.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        return data + "." + encoder.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
