package com.hmdp.upgrade;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

class AuthTest {
    String secret="test-only-0123456789-0123456789-0123456789";
    String signed(String payload) throws Exception {
        Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return "Bearer "+payload+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void validToken() throws Exception { assertEquals(7,new Auth(secret).verify(signed("7:user:"+(System.currentTimeMillis()/1000+60))).user()); }
    @Test void rejectsExpired() throws Exception { String token=signed("7:user:1");assertThrows(Problem.class,()->new Auth(secret).verify(token)); }
    @Test void rejectsTampering() throws Exception { String token=signed("7:user:"+(System.currentTimeMillis()/1000+60)).replace("7:user","7:admin");assertThrows(Problem.class,()->new Auth(secret).verify(token)); }
    @Test void rejectsMissing() { assertThrows(Problem.class,()->new Auth(secret).verify(null)); }
    @Test void metricsTokenOnlyAuthorizesPrometheusGet() {
        var auth=new Auth(secret);org.springframework.test.util.ReflectionTestUtils.setField(auth,"metricsToken","metrics-test-token-01234567890123456789");
        assertTrue(auth.metrics("/actuator/prometheus",true,"Bearer metrics-test-token-01234567890123456789"));
        assertFalse(auth.metrics("/actuator/prometheus",false,"Bearer metrics-test-token-01234567890123456789"));
        assertFalse(auth.metrics("/v2/admin/cache",true,"Bearer metrics-test-token-01234567890123456789"));
        assertFalse(auth.metrics("/actuator/prometheus",true,"Bearer wrong"));
    }
}
