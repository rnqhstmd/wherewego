package com.wherewego.domain.auth.jwt;

import java.time.Instant;

public sealed interface JwtValidationResult permits JwtValidationResult.Valid, JwtValidationResult.Invalid {

    record Valid(Long userId, Instant expiresAt) implements JwtValidationResult { }

    enum Invalid implements JwtValidationResult {
        EXPIRED,
        INVALID_SIGNATURE,
        INVALID_TYPE,
        MALFORMED
    }
}
