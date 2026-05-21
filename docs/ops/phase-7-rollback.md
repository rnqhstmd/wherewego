# Phase 7 운영 플레이북 — 태그 3종 리뉴얼

> Phase 7 (V006 단일 합본 마이그레이션) 배포·관찰·롤백 절차. PR 머지 후 운영 시 이 문서를 참조한다.

## 배포 절차 (단일 시점)

### 1. 사전 준비
- 새 코드 빌드 + 스테이징 검증 (REEL/WISH/MEMORY 정상 동작 확인)
- V006 SQL 리뷰 완료 (`backend/apps/wherewego-api/src/main/resources/db/migration/V006__renew_tag_constraint_and_migrate.sql`)
- 1인 운영 환경 — DB 백업 시점 확인 (배포 직전 스냅샷)

### 2. 배포 윈도우 (~5~10초 다운타임)

```text
[트래픽 차단] LB/리버스 프록시 maintenance 페이지 또는 503
      ↓
[V006 Flyway 마이그레이션 적용] 단일 트랜잭션, <1초 예상
   - ALTER (CHECK 일시 확장 PLACE/REEL/WISH/MEMORY)
   - UPDATE pins SET tag='REEL' WHERE tag='PLACE'
   - ALTER (CHECK 최종 축소 REEL/WISH/MEMORY)
      ↓
[새 코드 배포/재시작]
      ↓
[헬스체크 통과 확인]
      ↓
[트래픽 복구]
```

### 3. 사후 확인 (배포 직후 5분)

| 항목 | 확인 SQL/명령 | 기대값 | 관련 AC |
|------|---------------|--------|---------|
| PLACE 잔존 | `SELECT count(*) FROM pins WHERE tag='PLACE'` | 0 | AC-3 |
| CHECK 정의 | `\d+ pins` 또는 `pg_get_constraintdef('chk_pins_tag'::regclass)` | `IN ('REEL','WISH','MEMORY')` | AC-4 |
| 챗봇 신규 등록 | 카카오톡으로 인스타 URL 1건 공유 → DB 조회 | tag='REEL' | AC-1 |
| 웹 등록 패널 | `/map` → "새 핀 추가" 클릭 | "설렘"/"추억" 2종만 노출 | AC-2 |
| 지도 마커 | 그룹에 REEL/WISH/MEMORY 각 핀 1건 이상 → /map 확인 | 3종 시각 구분 | AC-5 |
| 룰렛 토글 OFF | "오늘 어디 갈까?" → 후보 풀 | REEL+WISH만 | AC-6 |
| 룰렛 토글 ON | "추억 핀도 포함" 체크 → 후보 풀 | REEL+WISH+MEMORY | AC-7 |

### 4. M1 fallback 관찰 가이드

활성 세션이 캐시된 PLACE 응답을 보유한 경우, 프론트엔드는 알 수 없는 enum을 WISH(민트 동그라미)로 silent 렌더링한다.

- **30초 polling**(`useGroupPinSync`)으로 자동 갱신되어 ~30초 내 자연 해소
- **감지 수단**: 브라우저 콘솔 또는 Sentry에서 `console.warn` 메시지 `"[PinDot] unknown tag value, falling back to WISH"` 류 검색 (Phase 7 후속 작업으로 추가됨)
- **관찰 기간**: 배포 직후 5분간 모니터링 권장. 비정상 빈도(분당 10건↑) 발생 시 데이터 불일치 의심 → DB의 `tag` 분포 재확인

---

## 롤백 절차

> ⚠️ **WISH 의미 손실 경고**: V006 적용 후 신규 등록된 WISH 핀은 롤백 시 PLACE로 흡수되어 사용자 의도("설렘")가 손실된다. REEL→PLACE는 의미 보존(둘 다 챗봇 경로).

### 1. 트래픽 차단

### 2. Down-migration SQL 실행

`psql -1` 또는 동일 트랜잭션 단위로 실행. **단일 트랜잭션이므로 부분 적용 위험 없음.**

```sql
BEGIN;

-- 1) CHECK 일시 확장: PLACE 다시 허용
ALTER TABLE pins DROP CONSTRAINT IF EXISTS chk_pins_tag;
ALTER TABLE pins ADD CONSTRAINT chk_pins_tag
    CHECK (tag IN ('PLACE', 'REEL', 'WISH', 'MEMORY'));

-- 2) REEL → PLACE, WISH → PLACE 일괄 변환
--    (WISH 손실 가능성 있음 — 위 경고 참조)
UPDATE pins SET tag = 'PLACE' WHERE tag IN ('REEL', 'WISH');

-- 3) CHECK 최종 축소: V005 상태(PLACE/MEMORY만 허용)
ALTER TABLE pins DROP CONSTRAINT IF EXISTS chk_pins_tag;
ALTER TABLE pins ADD CONSTRAINT chk_pins_tag
    CHECK (tag IN ('PLACE', 'MEMORY'));

-- 4) Flyway 메타데이터 정리: V006 실행 기록 삭제
--    (다시 forward migrate 시 V006이 재실행되도록)
DELETE FROM flyway_schema_history WHERE version = '006';

COMMIT;
```

### 3. 구코드 배포 및 재시작
PLACE/MEMORY 2종 enum을 사용하던 이전 릴리스 태그로 롤백.

### 4. 헬스체크 통과 확인

### 5. 트래픽 복구

### 6. 사후 검증
- `SELECT tag, COUNT(*) FROM pins GROUP BY tag` → PLACE/MEMORY 2종만
- 챗봇 신규 등록 → tag='PLACE' (구코드 기본값)
- 지도 마커 정상 렌더링

---

## 위험 케이스 시나리오

| 시나리오 | 대응 |
|----------|------|
| V006 적용 중 실패 (네트워크/DB 오류) | Flyway 자동 롤백 (단일 트랜잭션). DB는 V005 상태 유지. 코드 배포는 보류 후 V006 재시도 |
| V006 적용 후 코드 배포 실패 | DB는 새 enum, 코드는 구버전 → PLACE 등록 시도 시 DB CHECK 거부 (`PinV1Controller`가 IllegalArgumentException → 400 반환). 즉시 코드 롤백 또는 재배포 |
| 활성 세션이 캐시된 PLACE 응답 보유 | M1 fallback이 WISH로 렌더. 30초 polling으로 자연 해소 |
| V006 후 누군가 SQL로 직접 PLACE INSERT 시도 | CHECK 제약이 거부 (정상 동작). FlywayMigrationTest에 회귀 보호 케이스 포함 |
| 운영 중 알 수 없는 tag 값 발생 (이론적) | M1 fallback WISH 렌더 + `console.warn` 로깅. DB 직접 조회로 원인 추적 |

---

## 관련 산출물 위치

- 마이그레이션 SQL: `backend/apps/wherewego-api/src/main/resources/db/migration/V006__renew_tag_constraint_and_migrate.sql`
- 회귀 테스트: `backend/apps/wherewego-api/src/test/java/com/wherewego/migration/FlywayMigrationTest.java`
- PRD: `.dev/feat-phase-7-tag-renewal/prd.md`
- 설계서: `.dev/feat-phase-7-tag-renewal/design.md`
- 컨텍스트: `context/tag/{README,glossary,architecture,status}.md`
