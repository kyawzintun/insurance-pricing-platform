package com.insurance.platform.pricing.config;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import com.insurance.platform.pricing.RsaTestTokens;
import static org.assertj.core.api.Assertions.*;

class RsaJwtConfigurationTests {
    private final ClassPathResource publicKey = new ClassPathResource("keys/dev-jwt-public.pem");
    private final String claims = "{\"sub\":\"00000000-0000-0000-0000-000000000001\",\"roles\":[\"ADMIN\"],\"iss\":\"insurance-auth-service\",\"exp\":"
            + (Instant.now().getEpochSecond()+300) + "}";
    private void decode(String token) {
        new JwtConfiguration().jwtDecoder(publicKey, "insurance-auth-service").decode(token);
    }
    @Test void acceptsOnlyConfiguredRsaSignature() throws Exception {
        decode(RsaTestTokens.sign(claims));
        for (String rejected : RsaTestTokens.rejectedTokens()) {
            assertThatThrownBy(() -> decode(rejected)).isInstanceOf(RuntimeException.class);
        }
        var e = Base64.getUrlEncoder().withoutPadding();
        String payload = e.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        String data = e.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8))+"."+payload;
        var mac = Mac.getInstance("HmacSHA256");
        // Classic RSA/HMAC confusion attack using the publicly available PEM bytes.
        mac.init(new SecretKeySpec(publicKey.getContentAsByteArray(), "HmacSHA256"));
        String hs256 = data+"."+e.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        String none = e.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))+"."+payload+".";
        var parts = RsaTestTokens.sign(claims).split("\\.");
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]); signature[0] ^= 1;
        String invalid = parts[0]+"."+parts[1]+"."+e.encodeToString(signature);
        for (String rejected : List.of(hs256, none, invalid, "malformed")) {
            assertThatThrownBy(() -> decode(rejected)).isInstanceOf(RuntimeException.class);
        }
        assertThatThrownBy(() -> decode(RsaTestTokens.sign(claims.replace("insurance-auth-service","wrong-issuer"))))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> decode(RsaTestTokens.sign(claims.replaceAll("[0-9]+}$", "1}"))))
                .isInstanceOf(RuntimeException.class);
    }
    @Test void invalidMissingKeysAndBlankIssuerFailClosed() {
        for (String value : List.of("", "invalid-private-content")) {
            assertThatThrownBy(() -> new JwtConfiguration().jwtDecoder(new ByteArrayResource(value.getBytes(StandardCharsets.UTF_8)), "issuer"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("AUTH_JWT_PUBLIC_KEY")
                    .hasMessageNotContaining("invalid-private-content");
        }
        assertThatThrownBy(() -> new JwtConfiguration().jwtDecoder(new ClassPathResource("missing.pem"), "issuer"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtConfiguration().jwtDecoder(publicKey, " "))
                .isInstanceOf(IllegalStateException.class);
    }
}
