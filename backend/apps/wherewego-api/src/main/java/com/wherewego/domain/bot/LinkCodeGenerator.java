package com.wherewego.domain.bot;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class LinkCodeGenerator {

    private static final int CODE_BOUND = 1_000_000;

    private final SecureRandom random = new SecureRandom();

    public String generate6Digits() {
        int value = random.nextInt(CODE_BOUND);
        return String.format("%06d", value);
    }
}
