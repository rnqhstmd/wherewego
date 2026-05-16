package com.wherewego.domain.group;

import java.time.ZonedDateTime;

/**
 * 그룹 생성 결과. Service → Controller 도메인 결과 DTO.
 */
public record GroupCreatedResult(Long groupId, String name, ZonedDateTime createdAt) {
}
