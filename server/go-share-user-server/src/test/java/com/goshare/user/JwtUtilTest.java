package com.goshare.user;

import com.goshare.common.security.JwtUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JWT 签发/解析：正常签发解析、角色携带、伪造密钥/非法令牌拒绝
 */
class JwtUtilTest {

    private static final String SECRET = "goshare-jwt-secret-key-0123456789abcdefghij";
    private static final String WRONG_SECRET = "another-secret-key-abcdefghij-0123456789xy";

    private JwtUtil newUtil() {
        return new JwtUtil(SECRET, 1L);
    }

    @Test
    void createAndParse() {
        JwtUtil util = newUtil();
        String token = util.createToken(1001L, "PASSENGER");
        Claims claims = util.parseToken(token);
        assertEquals("1001", claims.getSubject());
        assertEquals("PASSENGER", claims.get("role"));
    }

    @Test
    void wrongSecretRejected() {
        JwtUtil signer = newUtil();
        JwtUtil verifier = new JwtUtil(WRONG_SECRET, 1L);
        String token = signer.createToken(1L, "DRIVER");
        assertThrows(Exception.class, () -> verifier.parseToken(token));
    }

    @Test
    void illegalTokenRejected() {
        JwtUtil util = newUtil();
        assertThrows(Exception.class, () -> util.parseToken("not-a-jwt"));
        assertThrows(Exception.class, () -> util.parseToken(""));
        assertThrows(Exception.class, () -> util.parseToken(null));
    }

    @Test
    void tamperedTokenRejected() {
        JwtUtil util = newUtil();
        String token = util.createToken(1L, "PASSENGER");
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThrows(Exception.class, () -> util.parseToken(tampered));
    }

    @Test
    void driverRoleRoundTrip() {
        JwtUtil util = newUtil();
        Claims claims = util.parseToken(util.createToken(2L, "DRIVER"));
        assertEquals("DRIVER", claims.get("role"));
    }
}
