package com.mockinterview.backend.security;

import com.mockinterview.backend.entity.Role;
import com.mockinterview.backend.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", "test-secret-key-at-least-32-bytes-long-for-hs256");
        ReflectionTestUtils.setField(jwtUtil, "expirationMs", 3600000L);
    }

    private User someUser() {
        User user = new User();
        user.setId(42L);
        user.setEmail("someone@example.com");
        user.setRole(Role.USER);
        return user;
    }

    @Test
    void generatedTokenRoundTripsEmailAndRole() {
        String token = jwtUtil.generateToken(someUser());

        assertThat(jwtUtil.extractEmail(token)).isEqualTo("someone@example.com");
        assertThat(jwtUtil.extractRole(token)).isEqualTo("USER");
        assertThat(jwtUtil.isTokenValid(token)).isTrue();
    }

    @Test
    void twoTokensIssuedInTheSameInstantForTheSameUserAreStillDistinct() {
        // Regression test: before the jti claim was added, two tokens generated within the
        // same clock-second for the same user were byte-for-byte identical, which violated
        // the unique constraint on Token.tokenValue when both were persisted (see AuthService).
        User user = someUser();
        String first = jwtUtil.generateToken(user);
        String second = jwtUtil.generateToken(user);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void expiredTokenIsNotValid() {
        ReflectionTestUtils.setField(jwtUtil, "expirationMs", -1000L); // already expired the moment it's issued
        String token = jwtUtil.generateToken(someUser());

        assertThat(jwtUtil.isTokenValid(token)).isFalse();
    }

    @Test
    void malformedTokenIsNotValid() {
        assertThat(jwtUtil.isTokenValid("not-a-real-jwt")).isFalse();
    }
}
