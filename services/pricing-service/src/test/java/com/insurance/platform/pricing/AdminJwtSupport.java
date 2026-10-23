package com.insurance.platform.pricing;

import java.time.Instant;

final class AdminJwtSupport {
    static String token(String role) throws Exception {
        return token(role, "insurance-auth-service", Instant.now().getEpochSecond() + 300);
    }
    static String token(String role, String issuer, long expiry) throws Exception {
        return sign("{\"sub\":\"00000000-0000-0000-0000-000000000001\",\"roles\":[\"" + role
                + "\"],\"iss\":\"" + issuer + "\",\"iat\":" + (Instant.now().getEpochSecond() - 60)
                + ",\"exp\":" + expiry + "}");
    }
    static String sign(String claims) throws Exception {
        return RsaTestTokens.sign(claims);
    }
}
