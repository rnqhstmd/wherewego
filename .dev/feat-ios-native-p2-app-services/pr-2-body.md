## Background

iOS 앱 전환(P5 푸시 UI)의 백엔드 기반으로 APNs 푸시와 기기 토큰 관리가 필요하다. 기존 알림은 웹 REST 폴링뿐이라 앱이 백그라운드일 때 파트너 핀 저장·커플 메시지·봇 처리 완료를 전달할 수단이 없었다. P2를 3개 PR로 분할한 두 번째 PR로, **PR-1(채팅+STOMP) 위에 스택**된다(base: `feat/ios-native-p2-chat`, PR #87). 모두 additive이며 기존 웹/카카오봇 경로는 무변경이다.

> ⚠️ 스택 PR: PR-1(#87)이 develop에 머지된 후 base를 develop로 리타겟하거나 bottom-up 머지가 필요하다.

## Summary

기기 토큰(`devices`) 모델과 APNs 푸시 인프라(pushy, .p8 토큰 기반)를 도입한다. `POST/DELETE /api/v1/devices`로 토큰을 등록·해지하고, 동일 토큰이 다른 사용자에게 남지 않도록 재할당(BR-9)한다. 파트너 핀 저장·커플 메시지·봇 결과 완료 세 시점에 본인/상대 기기로 푸시를 발송한다. 모든 푸시는 best-effort(트랜잭션 커밋 후 + try-catch 격리)로 핵심 흐름을 보호하며, `.p8`이 주입되지 않은 환경에서는 graceful no-op으로 동작한다.

## Changes

- **devices 모델/마이그레이션**: `devices`(user_id, platform, device_token) + V016. soft-delete 정합을 위해 `(user_id, device_token)` 부분 UNIQUE(`WHERE deleted_at IS NULL`)로 도입.
- **DeviceService**: `register`(FR-15 upsert — 존재 시 `updated_at`만 touch, AC-7) + BR-9 재할당(같은 토큰의 다른 사용자 행 soft delete) + 동시 등록 race의 `DataIntegrityViolationException` 폴백. `unregister`(FR-16), `removeByToken`(FR-19 죽은 토큰 정리, @Transactional).
- **APNs 인프라**: `ApnsProperties`(.p8/keyId/teamId/bundleId env 주입), `ApnsClientFactory`(pushy `ApnsClient`, 미구성 시 빈 미노출), `ApnsPushSender`(payload 빌드, 응답 거부 시 `BadDeviceToken`/`Unregistered` 토큰 정리, 전송 타임아웃 10s, 로그 토큰 마스킹).
- **푸시 트리거(FR-17)**: `PushNotificationService`가 fan-out(FR-20)으로 사용자 전 토큰에 발송. ① `PinV1Controller.createPin`에 파트너 푸시(별도 try-catch, `NotificationService` 무변경) ② `CoupleChatService` afterCommit 상대 푸시 ③ `BotChatProcessor`는 PLACE_CARDS 성공 시에만 푸시.
- **검증/안정화**: `/devices` 입력 검증(@Size), `ConstraintViolationException` → 400 핸들러 추가, `@Modifying` flushAutomatically.

## Audit Summary

통합 감사(QA + ZeroTrust) — 판정: 보안 HIGH 일부 수정 후 머지.

- **CRITICAL: 0** / HIGH: 3 / MEDIUM: 5 / LOW: 2
- 자기점검 해소: ApnsClientFactory destroyMethod NPE, ApnsPushSender 블로킹 타임아웃, AC-9 정리 트랜잭션(@Transactional removeByToken).
- 리뷰 수정(HIGH): 로그 deviceToken 마스킹, DELETE PathVariable 검증 + 400 핸들러.
- 이월(MEDIUM/LOW): `.p8` 파싱 실패 운영 가시성(health/알림은 .p8 도입 시), BR-9 reassign 정책 문서화, afterCommit 블로킹(스케일 시 @Async 분리), 1인 토큰 수 상한, fan-out 부분실패 가시성.
- 수용 기준: PR-2 범위 [Must] AC-7/8/9 전량 충족(인수 검증 ACCEPT). AC-1~6/14/15는 PR-1, AC-10~13은 PR-3.

상세: `.dev/feat-ios-native-p2-app-services/trust-ledger.md`

## Checklist

- [ ] `POST/DELETE /api/v1/devices` 등록·해지·재등록(updated_at 갱신) 동작 확인
- [ ] `.p8` 주입 환경에서 파트너 핀 저장/커플 메시지/봇 결과 푸시 수신 확인
- [ ] `.p8` 미주입 환경에서 graceful no-op(부팅·핀 저장·채팅 정상) 확인
- [ ] BadDeviceToken 응답 시 토큰 자동 정리 확인
- [ ] (스택) PR-1(#87) 머지 후 base 리타겟/리베이스, 이후 PR-3(계정 삭제) 진행
- [ ] (후속) device/push 단위·통합 테스트 추가 — 본 PR 미포함
