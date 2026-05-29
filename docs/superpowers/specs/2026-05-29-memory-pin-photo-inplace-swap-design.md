# 추억핀 사진 — 말풍선 제자리 전환 (메모 ↔ 사진) 설계

- 작성일: 2026-05-29
- 관련 레포: rnqhstmd/wherewego
- 상태: 설계 확정 (구현 전)
- 선행: Phase 13 추억핀 사진 업로드 ([#77](https://github.com/rnqhstmd/wherewego/pull/77)) — 본 설계는 Phase 13의 **사진 열람 UX를 교체**한다.

## 배경

Phase 13은 말풍선 메모 우측 **원형 썸네일** → 클릭 시 **전체화면 `PinPhotoViewer`(blur-up)** 로 원본을 여는 방식이다. 사용자 결정으로 이를 **말풍선 안 제자리 전환**으로 교체한다: 말풍선 구조를 유지한 채, 메모 우측 **정사각 썸네일**을 탭하면 **메모 텍스트 영역이 부드러운 애니메이션으로 사진으로 전환**된다. (설계 스펙 원문의 "엽서 뒤집기" 후속안은 footprint 불일치 리스크가 있어 채택하지 않고, 3D 플립 없는 제자리 크로스페이드 방식으로 확정.)

## 목표 / 비목표

**목표**
- 말풍선 모양·구조 유지(메모/장소/날짜/공유/메뉴 레이아웃 그대로).
- 메모 우측 정사각 썸네일 → 탭 → 메모 영역이 제자리에서 1:1 사진으로 부드럽게 전환, 다시 메모로 복귀.

**비목표**
- 3D 플립(엽서 뒤집기).
- 전체화면 사진 뷰어(`PinPhotoViewer`) — **제거**.
- 원본 전체(uncropped) 보기 — 1:1 cover 크롭으로 충분(사용자 결정).
- 백엔드/API/S3/업로드 흐름 변경 없음 (순수 프론트 표시 UX).

## 현재 구조 (변경 대상)

- `frontend/src/components/ui/SpeechBubblePopup.tsx` — 메모 블록(`lines.map`) + 우측 `memoThumbnail?: ReactNode` 슬롯(Phase 13). flex row, 우측 `flexShrink:0`.
- `frontend/src/app/map/_components/PinPopup.tsx` — `memoThumbnail`을 **원형 44px** img로 구성(클릭 → `setViewerOpen(true)`), `viewerOpen && photoUrl && photoThumbnailUrl` 일 때 `PinPhotoViewer` 렌더.
- `frontend/src/app/map/_components/PinPhotoViewer.tsx` — 전체화면 blur-up 뷰어. (제거 또는 인라인 재활용)

## 설계

### 상태 모델
PinPopup에 `photoExpanded: boolean` state(기존 `viewerOpen` 대체). 핀 전환 시 false로 리셋(기존 reset 블록 답습). `pin.tag === "MEMORY" && pin.photoThumbnailUrl`가 없으면 썸네일/전환 미노출(기존 게이트 유지, AC-11 보존).

### 상태 A — 기본 (메모 + 정사각 썸네일)
- 메모 텍스트(좌, `flex:1`) + **정사각 썸네일**(우, 약 52×52, `borderRadius` 10, `objectFit:cover`, `loading="lazy"`, cursor pointer). 현재 원형(`borderRadius:50%`, 44px)에서 변경.
- 썸네일 `onClick` → `setPhotoExpanded(true)`.

### 상태 B — 사진 펼침 (제자리)
- 메모+썸네일 행이 사라지고(crossfade) 같은 자리에 **1:1 정사각 사진**(말풍선 본문 폭 전체, `objectFit:cover`)이 펼쳐진다.
- 우하단 **↩ 버튼**(30px 원형, 반투명 ink 배경) → `setPhotoExpanded(false)`. 사진 영역 탭으로도 복귀(↩와 동일 동작).
- **blur-up**: 캐시된 `photoThumbnailUrl`을 `filter:blur(12px)` placeholder로 깔고 `photoUrl` `<img onLoad>` 완료 시 opacity 교차(스피너 없음). `PinPhotoViewer`의 blur-up 로직을 인라인 컴포넌트로 재활용.
- 장소·주소·날짜·공유·메뉴 행은 그대로(전환 영향 없음).

### 전환 애니메이션
- 컨테이너 높이: 메모 텍스트 높이 ↔ 정사각(폭=본문폭) 높이를 `transition: height 0.3s ease` 또는 max-height 기법으로 부드럽게.
- 내용 교차: 메모 노드 opacity 1→0 / 사진 노드 opacity 0→1 (0.25~0.3s). `prefers-reduced-motion` 시 즉시 전환.
- 3D 회전(rotateY) 없음.

### 컴포넌트 분해
- **`SpeechBubblePopup.tsx`**: 메모 영역을 "메모 노드 ↔ 확장 사진 노드" 전환 가능하게 확장. 신규 prop:
  - `expandedPhoto?: ReactNode` — 펼친 사진 노드(없으면 전환 비활성).
  - `showExpandedPhoto?: boolean` — true면 메모+썸네일 대신 `expandedPhoto`를 크로스페이드/높이 애니메이션으로 표시.
  - 기존 `memoThumbnail` 슬롯은 상태 A의 정사각 썸네일 용도로 유지.
  - 높이/opacity 전환은 SpeechBubblePopup 내부에서 처리(메모 줄 렌더를 소유하므로). 상태는 prop으로 주입(제어 컴포넌트).
- **`PinPopup.tsx`**: `photoExpanded` state, 정사각 `memoThumbnail`(onClick→true), `expandedPhoto` 노드(blur-up 사진 + ↩ onClick→false) 구성, `showExpandedPhoto={photoExpanded}` 전달. `viewerOpen`/`PinPhotoViewer` 렌더 제거.
- **`PinPhotoViewer.tsx`**: 전체화면 뷰어 제거. blur-up 로직을 인라인 `PinPhotoInline`(props: `thumbnailUrl`, `photoUrl`, `onBack`)으로 옮겨 PinPopup의 `expandedPhoto`에 사용.

## 영향 파일

| 파일 | 변경 |
|------|------|
| `frontend/src/components/ui/SpeechBubblePopup.tsx` | 메모 영역 메모↔사진 전환(높이+crossfade) + `expandedPhoto`/`showExpandedPhoto` prop |
| `frontend/src/app/map/_components/PinPopup.tsx` | 정사각 썸네일 트리거 + `photoExpanded` state + `expandedPhoto` 구성, PinPhotoViewer 제거 |
| `frontend/src/app/map/_components/PinPhotoViewer.tsx` | 전체화면 뷰어 제거 → `PinPhotoInline`(blur-up + ↩)로 대체/재배치 |
| (테스트) `SpeechBubblePopup.test.tsx` 등 | 썸네일 정사각/전환 토글 케이스 반영 |

## 엣지케이스

- 사진 없는 MEMORY 핀 / REEL·WISH: 썸네일·전환 미노출, 기존 레이아웃 불변.
- 핀 전환(다른 핀 선택): `photoExpanded` false 리셋.
- 수정 모드(`collapseBody`): 본문 접힘 → 썸네일/전환 미표시(현행 유지).
- 원본 로드 실패: blur-up 썸네일 유지(placeholder), 토스트는 과함 — 썸네일만 보이게.
- `prefers-reduced-motion`: 애니메이션 생략, 즉시 전환.

## 테스트 전략 (프론트 Vitest)

- `SpeechBubblePopup`: `memoThumbnail` 렌더(정사각), `showExpandedPhoto=true`면 메모 대신 `expandedPhoto` 렌더 / false면 메모 렌더.
- `PinPopup`: 썸네일 탭 → photoExpanded true → 사진/↩ 렌더, ↩ 탭 → false → 메모 복귀. 사진 없으면 썸네일 미렌더(AC-11 보존).
- 기존 `SpeechBubblePopup.test.tsx`(3건)·`PinPopupMemoEditor.test.tsx` 회귀 없음 확인.

## 비고

순수 프론트 표시 UX 변경. PR #77(Phase 13)이 미머지 상태이므로, 본 변경은 동일 브랜치에 이어 #77에 포함하거나 머지 후 후속 PR로 분리 — 구현 직전 결정한다.
