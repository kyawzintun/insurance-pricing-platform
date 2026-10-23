package com.insurance.platform.quote;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.security.Signature;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.converter.RsaKeyConverters;

/** Deterministic test-only signing key, never shipped in verifier application jars. */
public final class RsaTestTokens {
    private RsaTestTokens() {}
    public static String sign(String claims) throws Exception {
        var e = Base64.getUrlEncoder().withoutPadding();
        String data = e.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8))
                + "." + e.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        var signer = Signature.getInstance("SHA256withRSA");
        try (var input = new ClassPathResource("keys/dev-jwt-private.pem").getInputStream()) {
            signer.initSign(RsaKeyConverters.pkcs8().convert(input));
        }
        signer.update(data.getBytes(StandardCharsets.UTF_8));
        return data + "." + e.encodeToString(signer.sign());
    }
    public static java.util.List<String> rejectedTokens() throws Exception {
        String claims = "{\"sub\":\"00000000-0000-0000-0000-000000000001\",\"roles\":[\"ADMIN\",\"CUSTOMER\"],\"iss\":\"insurance-auth-service\",\"exp\":"
                + (java.time.Instant.now().getEpochSecond()+300) + "}";
        var e = Base64.getUrlEncoder().withoutPadding();
        String payload = e.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        String hs = e.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8))+"."+payload;
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(new ClassPathResource("keys/dev-jwt-public.pem").getContentAsByteArray(), "HmacSHA256"));
        String none = e.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))+"."+payload+".";
        var pair = UnrelatedKey.PAIR;
        String rsa = sign(claims); String data = rsa.substring(0, rsa.lastIndexOf('.'));
        var signer = Signature.getInstance("SHA256withRSA"); signer.initSign(pair.getPrivate()); signer.update(data.getBytes(StandardCharsets.UTF_8));
        String wrongRsa = data+"."+e.encodeToString(signer.sign());
        String rs512Data = e.encodeToString("{\"alg\":\"RS512\"}".getBytes(StandardCharsets.UTF_8))+"."+payload;
        var rs512Signer = Signature.getInstance("SHA512withRSA");
        try (var input = new ClassPathResource("keys/dev-jwt-private.pem").getInputStream()) {
            rs512Signer.initSign(RsaKeyConverters.pkcs8().convert(input));
        }
        rs512Signer.update(rs512Data.getBytes(StandardCharsets.UTF_8));
        String rs512 = rs512Data+"."+e.encodeToString(rs512Signer.sign());
        return java.util.List.of(rs512, hs+"."+e.encodeToString(mac.doFinal(hs.getBytes(StandardCharsets.UTF_8))), none, wrongRsa, "malformed");
    }
    // One unrelated pair per test JVM, used solely to prove wrong-key rejection.
    private static final class UnrelatedKey {
        static final java.security.KeyPair PAIR;
        static { try { var generator = java.security.KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); PAIR = generator.generateKeyPair(); }
                 catch (Exception ex) { throw new ExceptionInInitializerError(ex); } }
    }

}
