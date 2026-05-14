package com.wherewego.config.redis;

public record RedisNodeInfo(
        String host,
        int port
) { }
