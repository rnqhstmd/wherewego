package com.wherewego.domain.auth.jwt;

import com.wherewego.config.env.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtTokenProvider {

    private static final String TYP_CLAIM = "typ";
    private static final String TYP_ACCESS = "access";
    private static final String TYP_REFRESH = "refresh";

    private final JwtProperties props;
    private final SecretKey signingKey;

    public JwtTokenProvider(JwtProperties props) {
        this.props = props;
        this.signingKey = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String issueAccessToken(Long userId) {
        return issueToken(userId, TYP_ACCESS, props.accessTtlSeconds());
    }

    public String issueRefreshToken(Long userId) {
        return issueToken(userId, TYP_REFRESH, props.refreshTtlSeconds());
    }

    public JwtValidationResult parseAccessToken(String token) {
        return parse(token, TYP_ACCESS);
    }

    public JwtValidationResult parseRefreshToken(String token) {
        return parse(token, TYP_REFRESH);
    }

    private String issueToken(Long userId, String typ, long ttlSeconds) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttlSeconds);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId.toString())
                .claim(TYP_CLAIM, typ)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }

    private JwtValidationResult parse(String token, String expectedTyp) {
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token);
            Claims claims = jws.getPayload();

            JwtValidationResult typCheck = enforceType(claims, expectedTyp);
            if (typCheck != null) {
                return typCheck;
            }

            Long userId = Long.parseLong(claims.getSubject());
            Instant expiresAt = claims.getExpiration().toInstant();
            return new JwtValidationResult.Valid(userId, expiresAt);
        } catch (ExpiredJwtException e) {
            return JwtValidationResult.Invalid.EXPIRED;
        } catch (SignatureException e) {
            return JwtValidationResult.Invalid.INVALID_SIGNATURE;
        } catch (MalformedJwtException | UnsupportedJwtException | IllegalArgumentException e) {
            return JwtValidationResult.Invalid.MALFORMED;
        } catch (JwtException e) {
            return JwtValidationResult.Invalid.MALFORMED;
        }
    }

    private JwtValidationResult enforceType(Claims claims, String expectedTyp) {
        Object typ = claims.get(TYP_CLAIM);
        if (!(typ instanceof String typStr) || !expectedTyp.equals(typStr)) {
            return JwtValidationResult.Invalid.INVALID_TYPE;
        }
        return null;
    }
}
