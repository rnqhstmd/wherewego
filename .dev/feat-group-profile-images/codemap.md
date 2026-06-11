# 코드 맵: 그룹 대표 이미지·프로필 사진 (업로드/원형 크롭/썸네일 노출)

> BASE_BE = backend/apps/wherewego-api/src/main/java/com/wherewego

## 핵심 파일
- ${BASE_BE}/domain/user/UserModel.java:49 → `profileImageUrl` 필드 **이미 존재**(OAuth 가입 시 수집 추정). 사용자 지정 업로드로 갱신 대상
- ${BASE_BE}/domain/group/Group.java → 그룹 엔티티. 현재 `name`만 보유 — 대표 이미지 컬럼 없음
- ${BASE_BE}/domain/group/GroupSummary.java → 그룹 목록 항목 record(groupId, name, createdAt, memberCount) — 이미지·멤버 프사 추가 대상
- ${BASE_BE}/infrastructure/pin/S3PinPhotoStorage.java → S3 업로드 + webp 썸네일 생성 + immutable 캐시헤더. **재사용 1순위 패턴**
- ios/WhereWeGo/Features/Photo/ (ImageCropper.swift, SquareCropView.swift, PhotoPickerView.swift) → 기존 사진 선택·정사각 크롭 UI — 원형 크롭 변형 기반

## 참조 파일
- ${BASE_BE}/interfaces/api/group/ (GroupV1Controller/Dto/ApiSpec) → 그룹 API(목록·생성·멤버·관리)
- ${BASE_BE}/interfaces/api/me/ (MeV1Controller/Dto) → 내 정보 API — 프로필 사진 업로드 추가 대상
- ${BASE_BE}/domain/group/GroupMemberInfo.java → 그룹원 목록 DTO(GM-2) — 프사 URL 추가 대상
- ios/WhereWeGo/Features/Group/GroupListView.swift → 그룹 목록 탭(현재 멤버 수만 노출)
- ios/WhereWeGo/Features/Chat/DMListView.swift → 채팅 탭(방 목록) — 그룹 대표 이미지 썸네일 대상
- ios/WhereWeGo/Features/Chat/Group/GroupMessageRow.swift → 채팅 상세 메시지 행 — 상대 프사 노출 대상
- ios/WhereWeGo/Features/MyInfo/MyInfoView.swift → 내정보 탭 — 내 프사 지정 진입점
- ios/WhereWeGo/Features/Group/GroupCreateView.swift, GroupManageView.swift → 그룹 생성/관리 — 대표 이미지 지정/수정 진입점

## 설정
- ${BASE_BE}/config/env/S3Properties.java → bucket + `publicBaseUrl`(key→공개 URL 변환)
- ${BASE_BE}/domain/pin/PinPhotoStorage.java:33 → `StoredPhoto(photoKey, thumbnailKey)` 저장 계약
- ${BASE_BE}/config/s3/S3Config.java → S3 클라이언트 구성
