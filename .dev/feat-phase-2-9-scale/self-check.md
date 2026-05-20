# Phase 2.9 자기점검 결과

## 요약

- **CERTAIN Critical: 0건** (자동 수정 불필요, 자기점검 통과)
- CERTAIN Warning: 1건 (phase-review 이월)
- Info: 2건 (phase-review 이월)
- QUESTION: 3건 (phase-review 이월)

## SELF_CHECK_FINDINGS (Warning/Info — 중복 보고 방지용)

- [Warning] PinV1Dto.java:52 — `@JsonInclude(NON_NULL)`이 record 레벨에 직접 선언. Spring Boot 3.4.4 / Jackson 2.17.x에서는 정상 동작 보장되나, 전역 ObjectMapper 설정에 따라 동작이 달라질 수 있음. AC-0이 HTTP 응답 키 부재로 회귀 방어 중.
- [Info] PinV1Controller.java:78 — `page < 0` 검사 시점 도달 시 `page`는 null 아님 보장됨 (분기 흐름상). NPE 위험 없음.
- [Info] PinServiceIT.java:102 — `Thread.sleep(10)` 사용. 10ms는 매우 짧아 flaky 위험 낮으나, 향후 DB 시퀀스 기반 명시적 시간 주입이 더 견고.

## SELF_CHECK_QUESTIONS (사용자 확인 필요 — phase-review 검토 항목)

1. **JacksonConfig 전역 설정 확인 필요**
   - 위치: `backend/.../JacksonConfig.java` (있다면)
   - 질문: 전역 `ObjectMapper`에 `@JsonInclude` 정책이 `ALWAYS`나 `NON_NULL`로 강제되어 있는가?
   - 영향: `ALWAYS`면 PinListResponse의 `@JsonInclude(NON_NULL)`이 무시되어 legacy 응답에 `totalCount:null, hasNext:null`이 직렬화될 수 있음. AC-0 통합 테스트가 통과한다면 사실상 동작 정상.

2. **PinRepositoryImpl `PageRequest.of(page, size)` Sort 명시화 여부**
   - 위치: PinRepositoryImpl.java:51 등
   - 질문: 현재 `PageRequest.of(page, size)`는 정렬을 메서드명(`OrderByCreatedAtDesc`)에 의존. 향후 쿼리 변경 시 명시적 `Sort.by(Direction.DESC, "createdAt")` 추가가 안전한가?
   - 영향: 현재는 메서드명 파싱으로 정렬이 적용되어 정상. 단, 코드 리팩토링 시 정렬 누락 위험.

3. **PinV1ControllerIntegrationTest AC-2 단언 견고성**
   - 위치: PinV1ControllerIntegrationTest.java:519 (`containsExactly("items")`)
   - 질문: `containsExactly` 단언이 PinListResponse 필드 추가 시 무조건 실패. 의도된 회귀 가드인지 아니면 `containsExactlyInAnyOrder`/`containsOnly`가 더 정확한지?
   - 영향: 현재 단일 원소(`"items"`)라 차이 없음. 미래 필드 추가 시 테스트 깨짐 → 의도된 가드라면 유지, 아니면 변경.

## 스펙 충족 매트릭스 (요약)

- [Must] FR-1~3: ✅ 모두 통과
- [Should] FR-4, FR-5: ✅ 통과
- AC-0~8: ✅ 통과 (AC-2는 QUESTION-3과 연결)
- AC-9, AC-11: ⚠️ 프론트 코드 미변경. Phase 2.8 동작 유지로 간주하나 수동 시각 확인 필요
- AC-10, AC-12: ✅ 통과
