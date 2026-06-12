# pin 용어 사전

| 용어 | 설명 |
|------|------|
| Pin | 핀 엔티티. 컬럼: `id`, `group_id`, `place_name`, `address`, `latitude`, `longitude`, `instagram_url`(nullable), `memo`, `memo_source`(AUTO/MANUAL), `tag`(PLACE/MEMORY), `created_at`, `created_by` |
| group_id | 핀이 속한 그룹의 외래키. [[group]] 도메인 참조 |
| place_name | API 응답에서 가져온 상호명 (한국어) |
| address | API 응답 주소 (한국어) |
| latitude / longitude | 위경도. Mapbox 핀 표시에 필수 |
| instagram_url | 원본 릴스 URL. 챗봇 등록 시에만 채워짐. 웹 직접 등록은 nullable |
| tag | PLACE 또는 MEMORY. [[tag]] 도메인 참조. NOT NULL |
| memo_source | AUTO(챗봇 2초 룰) 또는 MANUAL(웹 직접 입력). [[memo]] 우선순위 정책 참조 |
| 중복 방지 | `UNIQUE(group_id, instagram_url) WHERE instagram_url IS NOT NULL`. 웹 직접 등록 핀은 중복 가능 |
| ~~visited~~ | **PRD에서 제거됨**. 방문 인증 기능 없음. 시각적 표현은 [[tag]]로 일원화 |
| 방문 감지 (Phase 10) | WISH/REEL 핀 100m 이내에 30초 이상 머물면 토스트 노출 → MEMORY 자동 전환 제안. `useVisitDetection` 훅. GPS 정확도 50m 초과 이벤트는 평가 스킵 |
| 30초 머무름 임계 (Phase 10) | 첫 진입 시각 기록 → 이후 콜백마다 시간 차이 계산. 30초 경과 + 100m 이내면 토스트 발동. setInterval 미사용 |
| 100m 감지 반경 (Phase 10) | Haversine 공식(프론트엔드 `_lib/roulette.ts:haversineKm` 재사용). 백엔드 거리 계산 없음. PostGIS 미도입 |
| GPS 정확도 게이트 (Phase 10) | `position.coords.accuracy ≤ 50m` 인 이벤트만 평가 사용. 50m 초과는 머무름 타이머에도 영향 없음 (보존) |
| `PinUpdateResult` (Phase 10) | `PinService.updatePin` 반환 record. `(PinSummary summary, boolean wasWishOrReelToMemory)`. 컨트롤러가 태그 전이 감지하여 VISIT_DETECTED 알림 호출 분기 |
| 세션 Set `shownPinIds` (Phase 10) | 메모리 내 `Set<number>`. "다음에 올게요"/MEMORY 전환 후 추가. MapClient unmount 시 자동 소멸 (localStorage 미사용) |
| `pins.visited_at` 컬럼 (Phase 10) | V010 마이그레이션으로 추가된 `TIMESTAMPTZ NULL`. `Pin.changeTag(MEMORY)`가 WISH/REEL → MEMORY 전이 시 `ZonedDateTime.now()` 기록. MEMORY 이외 전이는 변경하지 않음. `PinSummary`/`PinSummaryResponse` 노출 → VisitMemoSheet "다녀온 날" 표시 |
| BBox prefilter (Phase 10) | `useVisitDetection` 평가 시 Haversine 호출 전 `LAT_DEG_PER_METER × PROXIMITY_METERS` 박스로 후보 1차 필터링. 1000핀 환경에서 거리 계산을 99% 컷 (~10회로 축소) |
| 속도 게이트 (Phase 10) | `position.coords.speed > 1.4 m/s` (`WALKING_SPEED_MAX_MS`, 보행 속도 상한) 감지 시 모든 후보의 `firstEnterAt` clear. 차량 이동 추정 시 우연한 30초 통과를 방지 |
| `NotificationVisitWriter` (Phase 10) | `@Transactional(REQUIRES_NEW)` + `void writeOne`. 예외를 caller에 전파해 Spring `UnexpectedRollbackException` 회피. 호출자(`NotificationService.createForVisitDetected`)가 `DataIntegrityViolationException`(부분 UNIQUE 충돌)과 `RuntimeException`(per-receiver 격리)을 각각 catch |
| PinListResult | Phase 2.9 — `PinService.listGroupPinsPaged`의 페이지 모드 전용 결과 record. 필드: `List<PinSummary> items`, `long totalCount`, `boolean hasNext`. 항상 유의미한 값(null 없음). 인터페이스 레이어에서 `PinListResponse.fromPaged()`로 응답 매핑 |
| legacy 모드 | Phase 2.9 — 핀 목록 API가 `page`/`size` 파라미터 미전달일 때 동작. 기존 `{items}` 단일 응답 구조 유지. 룰렛 stale 재조회 + `/pins` 초기 fetch가 사용 |
| 페이지 모드 | Phase 2.9 — 핀 목록 API가 `page`(0-based)와 `size`(최대 100)를 둘 다 전달받았을 때 동작. 응답에 `totalCount`/`hasNext` 추가. `hasNext = (long)(page + 1) * size < totalCount` (long 캐스팅으로 오버플로 방지) |
| PIN_PAGE_PARAM_INVALID | Phase 2.9 — `page`/`size` 부분 전달, 음수/0, 비숫자 입력 시 400 응답 코드 |
| PIN_PAGE_SIZE_EXCEEDED | Phase 2.9 — `size>100` 시 400 응답 코드 (MAX_PAGE_SIZE 상한) |
| 추억핀 사진 (Phase 13) | MEMORY 핀 한정 사진 1장(선택). 추억 등록·방문 전환·핀 수정 3곳에서 업로드. REEL/WISH엔 미노출. instagram 미리보기와 무관한 순수 수동 업로드 |
| photo 컬럼 4개 (Phase 13) | V013 — `photo_key`(원본 S3 키)/`photo_thumbnail_key`(썸네일 키)/`photo_uploaded_by`(업로더 id)/`photo_uploaded_at`. 모두 nullable, DB엔 키만 저장(URL 미저장) |
| `PinPhotoStorage` (Phase 13) | 사진 스토리지 도메인 포트. `store(groupId, pinId, bytes, contentType)`→`StoredPhoto(photoKey, thumbnailKey)` + `deleteQuietly(키)`. 어댑터 `S3PinPhotoStorage`가 구현(AWS SDK v2 최초 도입). 의존성 역전으로 S3 SDK를 인프라에만 격리 |
| `photoUrl`/`photoThumbnailUrl` (Phase 13) | `PinSummary`/`PinSummaryResponse`의 조합 URL(DB 컬럼 아님). `PinService.toPublicUrl(key)=publicBaseUrl+"/"+key`(트레일링 슬래시 정규화). 전역 Jackson NON_NULL이라 사진 없으면 응답 키 누락 → 프론트 optional 타입 |
| S3 키 스킴 (Phase 13) | 원본 `pins/{groupId}/{pinId}/{uuid}.jpg`, 썸네일 `pins/{groupId}/{pinId}/{uuid}_thumb.webp`(동일 uuid 공유). 공개(public-read)+UUID(추측 불가), `Cache-Control: public, max-age=31536000, immutable`. LIST 미사용(키 DB 보관) |
| 업로드 검증 4단계 (Phase 13) | ① MEMORY 태그(서비스, `PIN_PHOTO_NOT_MEMORY`) ② Content-Type 화이트리스트+매직바이트(컨트롤러, `PIN_PHOTO_TYPE_INVALID`) ③ 크기 ≤2MB(`PIN_PHOTO_SIZE_EXCEEDED`) ④ 픽셀 장변 ≤4096(어댑터, `PIN_PHOTO_DIMENSION_EXCEEDED`). 원본 성공+썸네일 실패 시 원본 정리(원자성) |
| 태그 이탈 시 사진 보존 (Phase 13) | MEMORY→다른 태그 변경 시 사진 레코드/S3 보존, UI만 tag로 게이트. 단 핀 소프트 삭제 시에는 S3 best-effort 정리(공개 URL 영구 노출 방지) |
| 체크인 (visit-checkin v2, 미구현) | 도착 감지에서 "혼자예요" 선택 시의 개인 방문 기록. 핀 태그 불변(위시 보존) + `pin_visits` upsert + 채팅 핀 카드 공유. [visit-checkin-policy.md](visit-checkin-policy.md) |
| 동행 선언 (visit-checkin v2, 미구현) | 도착 감지에서 멤버 2명 이상 선택 → MEMORY 전환(1회·멱등)의 트리거. 기계 판정(동시 GPS 매칭) 없이 방문자가 선언, union 합산으로 동시 제출 충돌 해소 |
| SELF / TAGGED (visit-checkin v2, 미구현) | `pin_visits.source` — 본인 폰 체크인(검증) / 타인의 동행 선언(주장). TAGGED는 본인 체크인 시 SELF 승격. 오입력 정정 기능은 **베타 수용으로 미구현 확정**(채팅 카드 공개·수동 편집이 수렴 장치) |
