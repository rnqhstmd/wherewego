# 설계서: 그룹 대표 이미지 · 프로필 사진 (GP-1)

> 2026-06-11 확정 (사용자 승인). PRD: prd.md. 설계 규모: 대형.

## 0. 설계 원칙

- 핀 사진 업로드 파이프라인(컨트롤러 3중 검증 → 서비스 → S3 저장 + webp 썸네일 → 공개 URL)을 그대로 복제·일반화한다. 새 발명 없음
- 사용자 프사는 모든 응답에서 단일 URL 필드(`profileImageUrl`, thumb 우선)로 통일 — 기존 계약(`UserResponse.profileImageUrl`) 재사용
- 채팅방 목록 백엔드는 무변경 — iOS가 `groupId`로 그룹 목록(GroupContext)의 이미지·멤버 정보를 조인해 썸네일/콜라주를 그린다
- 채팅 메시지 프레임은 서버 확장(`senderProfileImageUrl`) — 탈퇴 발신자도 프사 유지(BR-6), 닉네임 배치 조회에 합산되어 쿼리 수 불변

## 1. 백엔드 (BASE = backend/apps/wherewego-api/src/main/java/com/wherewego)

### 1.1 마이그레이션 V022

`backend/apps/wherewego-api/src/main/resources/db/migration/V022__group_image_and_user_avatar.sql`:
```sql
ALTER TABLE groups ADD COLUMN image_key VARCHAR(255);
ALTER TABLE groups ADD COLUMN image_thumb_key VARCHAR(255);
ALTER TABLE users  ADD COLUMN profile_image_key VARCHAR(255);
ALTER TABLE users  ADD COLUMN profile_image_thumb_key VARCHAR(255);
```
기존 `users.profile_image_url`(카카오 URL)은 보존 — 업로드 키가 없을 때의 폴백.

### 1.2 스토리지: AvatarStorage (신설)

- `domain/image/AvatarStorage.java`: `StoredAvatar store(String keyPrefix, byte[] imageBytes, String contentType)` / `void deleteQuietly(String key, String thumbKey)` — `record StoredAvatar(imageKey, thumbKey)`
- `infrastructure/image/S3AvatarStorage.java`: S3PinPhotoStorage 패턴 복제(원본 jpg + 장변 256px webp 썸네일 — Scrimage `image.max(256,256).bytes(WebpWriter.DEFAULT)`, CACHE_CONTROL "public, max-age=31536000, immutable"). 키: `groups/{groupId}/avatar/{uuid}.jpg`(+`_thumb.webp`), `users/{userId}/avatar/{uuid}.jpg`
- `PinV1Controller`의 `isAllowedImageMagic` + `ALLOWED_PHOTO_TYPES` + `MAX_PHOTO_SIZE(2MB)`를 `interfaces/api/support/ImageUploadGuard.java`로 추출, 핀 컨트롤러는 위임(동작 무변경)

### 1.3 엔티티·프로필 합성

- `Group`: `imageKey`/`imageThumbKey` 필드 + `updateImage(key, thumbKey)` / `clearImage()`
- `UserModel`: `profileImageKey`/`profileImageThumbKey` + `updateProfileImage(...)` / `clearProfileImage()`(profile_image_url 도 null 처리 = "없음" 상태 확정)
- 유효 프사 URL 규칙(단일 헬퍼): `thumbKey → toPublicUrl(thumbKey)`, 없으면 `profileImageUrl`(카카오), 둘 다 없으면 null. toPublicUrl = S3Properties.publicBaseUrl 끝슬래시 제거 + "/" + key (PinService.toPublicUrl 동일)
- `UserRepository.findNicknamesByIds` 유지 + `findProfilesByIds(Set<Long>) → Map<Long, UserProfile(nickname, effectiveImageUrl)>` 추가

### 1.4 API

| 메서드 | 경로 | 권한 | 응답 |
|---|---|---|---|
| POST (multipart `file`) | `/api/v1/groups/{groupId}/image` | 활성 멤버 | `GroupImageResponse(imageUrl, imageThumbUrl)` |
| DELETE | `/api/v1/groups/{groupId}/image` | 활성 멤버 | 동일(null) |
| POST (multipart `file`) | `/api/v1/users/me/profile-image` | 본인 | `UserResponse` |
| DELETE | `/api/v1/users/me/profile-image` | 본인 | `UserResponse` |

- 검증 = 핀과 동일 3중(contentType 화이트리스트 → 2MB → 매직바이트), ImageUploadGuard 사용. 에러타입은 범용 `IMAGE_*` 신설(HTTP 의미는 PIN_PHOTO_*와 동일)
- 그룹 권한 = GroupMemberService 활성 멤버 검증 재사용(그룹명 수정 PATCH 와 동일 경로). 교체/제거 시 이전 키 best-effort 삭제

### 1.5 그룹 목록·멤버 목록 확장

- `GroupSummary`(record)에 `imageUrl`/`imageThumbUrl` 추가(projection 확장 — GroupMemberRepositoryImpl.listActiveGroupSummariesByUserId)
- 신설 `GroupMemberPreview(userId, nickname, profileImageUrl, joinedAt)` — `GroupMemberRepository.listActiveMembersByGroupIds(Collection<Long>)` IN 쿼리 1회 + 서비스 그룹핑(가입순 joined_at ASC, id ASC)
- `listMyGroups` 반환 = `GroupListItem(summary, members)` 조립 → `GroupSummaryResponse`에 `imageUrl`/`imageThumbUrl`/`members[{userId, nickname, profileImageUrl}]` 추가
- `GroupMemberInfo`에 `profileImageUrl` 추가(FR-9) → `MemberResponse` 동반 확장
- GroupChatService.listRooms 가 GroupSummary 를 소비하는 부분은 시그니처 정합만 유지(채팅 목록 응답 무변경)

### 1.6 채팅 메시지 프레임 확장

- `GroupChatService.nicknamesOf` → `profilesOf`(닉네임+유효 프사 URL 동시 배치, findProfilesByIds)
- `GroupChatMessageFrame`에 `senderProfileImageUrl` 추가(발신자 NULL 이면 null) — additive 계약, iOS decodeIfPresent 하위호환

### 1.7 FR-7 카카오 동기화 중단 + FR-8 정원 8

- `UserLoginPersistence`의 기존 사용자 경로 `existing.updateProfile(...)` 호출 제거(카카오 웹 upsertAndIssueTokens·네이티브·애플 전부 — 신규 생성 시에만 수집). `UserModel.updateProfile` 미사용화되면 제거
- `GroupMemberService.MAX_GROUP_MEMBERS = 10 → 8` (`>=` 검사라 기존 9~10명 그룹 신규 가입 자동 차단, 강제 퇴장 없음)

### 1.8 테스트

- 신규: 그룹 이미지 업로드/삭제 권한(비멤버 403)·검증(타입/크기/매직), 프사 업로드/삭제, 유효 URL 폴백(키>카카오>null), 그룹 목록 멤버 프리뷰, 프레임 senderProfileImageUrl, 정원 8 경계
- 수정: 로그인 upsert(재로그인 프로필 비갱신 기대값), 기존 정원 10 가정 테스트

## 2. iOS (ios/WhereWeGo)

### 2.1 공용 컴포넌트 (신설)

- `Features/Common/AvatarView.swift`: `(imageUrl: String?, name: String, size: CGFloat)` — AsyncImage 성공 시 원형, 실패/없음 시 이니셜+틴트 원(현행 senderAvatar 스타일). 틴트색 = name 해시 → WGColor 계열 고정 팔레트 4~6색
- `Features/Common/GroupAvatarView.swift`: `(imageUrl: String?, members: [...], size)` — 이미지 있으면 원형, 없으면 콜라주(1=단일/2=대각/3=삼각/4=2×2, 셀=미니 AvatarView, 가입순 ≤4)
- `Features/Photo/SquareCropView`에 `maskShape: CropMask = .square` 파라미터(.circle 이면 마스크·가이드만 원형, 결과물은 동일 1:1 UIImage — BR-1). 핀 호출부 무변경

### 2.2 모델·API

- iOS `GroupSummary`에 `imageUrl`/`imageThumbUrl`/`members: [GroupMemberPreview(userId, nickname, profileImageUrl)]` (decodeIfPresent)
- `GroupAPI`: `uploadGroupImage(groupId, jpegData)` multipart(핀 uploadPhoto 패턴)·`deleteGroupImage(groupId)`
- UserAPI(CurrentUser 경로): `uploadProfileImage(jpegData)` / `deleteProfileImage()` — 응답 UserResponse
- `CurrentUser`에 `@Published profileImageUrl: String?` 추가(refresh 시 저장)
- `GroupChatFrame`에 `senderProfileImageUrl: String?` 디코딩 추가
- 업로드 전 처리: `ImageCropper.resizeAndCompress`(2MB 게이트) 그대로

### 2.3 화면 배선

| 화면 | 변경 |
|---|---|
| GroupListView.groupCard | 좌측 14pt 점 → GroupAvatarView(44pt). "멤버 N명" 텍스트 → 멤버 전원 AvatarView(20pt) 가입순 일렬(살짝 겹침), 인원 수 텍스트 제거 |
| DMListView.DMRoomRow | bubble 아이콘 → GroupAvatarView(44pt). room.groupId 로 GroupContext.groups 조인(미로딩 시 현행 아이콘 폴백) |
| GroupMessageRow.senderAvatar | AvatarView(frame.senderProfileImageUrl, senderName, 32) 교체 |
| MyInfoView.userSection | person.fill 원 → AvatarView(44pt, CurrentUser) + 탭 액션시트(앨범에서 선택/사진 제거) → PhotoPickerView → 원형 크롭 → 업로드 |
| GroupCreateView | 그룹명 위 원형 이미지 자리(120pt, 카메라 placeholder) + 탭=피커→원형 크롭. 생성 = ①그룹 생성 ②이미지 업로드 2단계. ② 실패 시 정상 진입 + "이미지는 그룹 관리에서 다시 올릴 수 있어요" 토스트 |
| GroupManageView | 상단 그룹 이미지 섹션(이미지/콜라주 80pt + 변경·제거, 멤버 누구나). 멤버 행 Circle → AvatarView(+profileImageUrl) |

## 3. 구현 순서 (4단계)

| 단계 | 내용 | 검증 |
|---|---|---|
| B1 | V022 + AvatarStorage/S3AvatarStorage + ImageUploadGuard 추출 + 엔티티 확장 + FR-7 동기화 중단 + 정원 8 | compileJava + 관련 단위 테스트 |
| B2 | 업로드/삭제 API 4종 + 그룹 목록 멤버 프리뷰 + GroupMemberInfo·프레임 확장 + 테스트 | compileTestJava + 신규 테스트 |
| B3 | iOS 공용 컴포넌트 + 모델·API 확장 | Windows 빌드 불가 → 구문 정합 자체 점검 |
| B4 | iOS 화면 5곳 배선 + 업로드 플로우 | iOS CI(GitHub Actions) |

## 4. 비판 검토 반영 (자가 수행)

- [SIMPLIFY 채택] 채팅방 목록 응답 확장 대신 iOS GroupContext 조인 — 백엔드 무변경
- [CHALLENGE 해소] 정원 10→8: `>=` 검사라 기존 그룹 보정 불요, 상수 1곳
- [RISK 명시] 카카오 URL 만료 가능 — 기존 동작 동일(신규 리스크 아님), 로드 실패 이니셜 폴백(AC-8)이 안전망
- [ROOT-CAUSE] 프사 "제거" = 카카오 URL 포함 전부 null. 동기화 중단으로 자동 복원 없음 — 새 업로드로만 재설정
