# 자기점검 — PR-2 (APNs+devices)

## Critical (자동 수정 완료)
- [Critical/해소] ApnsClientFactory `@Bean(destroyMethod="close")` + null 반환 → 컨텍스트 종료 NPE. destroyMethod 제거(ApnsClient는 Closeable, non-null만 자동 close).
- [Critical/해소] ApnsPushSender `.get()` 무제한 블로킹 → `.get(10s)` 타임아웃(워커/afterCommit 스레드 점유 상한).
- [Critical/해소, AC-9] BadDeviceToken 정리가 `@Transactional` 없이 `@Modifying` 호출 → `TransactionRequiredException` 무음 실패. `DeviceService.removeByToken(token)` @Transactional 신설, ApnsPushSender가 DeviceService 경유 호출(APNs 블로킹은 트랜잭션 밖 유지). + DeviceJpaRepository @Modifying에 flushAutomatically 추가.

## Warning/Info (phase-review 이월)
- [Info] DeviceService.upsert touch 후 반환 객체 updatedAt 미reload(현재 응답은 deviceId만이라 무영향).
- [Info] ApnsClientFactory: isConfigured 인데 .p8 파싱 실패 시 null 무음 폴백(운영 가시성 — log.error는 있음). 알림 채널 전파 고려.
- [Info] pushToUser catch(RuntimeException) → Exception 확장 일관성(ApnsPushSender가 이미 전부 격리하므로 영향 낮음).
- [Info] DeviceV1ApiSpec @Parameter 예시 추가(문서 품질).

## AC 충족 (자기점검)
- AC-7(device upsert updated_at 갱신): touchById @Modifying + 부분 UNIQUE. 충족.
- AC-8(파트너 핀 저장 APNs): PinV1Controller pushPinSaved → findOtherActiveMemberIds fan-out. 충족.
- AC-9(BadDeviceToken 행 삭제): handleRejection → DeviceService.removeByToken(@Transactional). 수정 후 충족.
