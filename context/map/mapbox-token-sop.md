# Mapbox 토큰 회전 SOP (운영자 가이드)

> 본 문서는 운영자가 Mapbox 액세스 토큰을 안전하게 교체하는 절차를 단계별로 안내합니다.
> Mapbox 대시보드 접근 권한 + 배포 플랫폼(Vercel 등) 환경 변수 권한이 필요합니다.

## 적용 시점

- 토큰 노출 의심 (PR 실수, 클라이언트 디버그 노출 등)
- 정기 로테이션 (분기 1회 권장)
- 신규 도메인 추가 (URL Restriction 확장 필요 시)

## 절차

### 1) 신규 토큰 발급

- Mapbox 대시보드(https://account.mapbox.com/access-tokens/) 진입
- "Create a token" 클릭
- Public scope: `styles:read`, `fonts:read`, `tilesets:read`, `datasets:read` 등 운영 필수 스코프만 선택
- 토큰명 규칙: `<운영자가 채울 형식, 예: wherewego-{env}-{yyyymmdd}>`

### 2) URL Restriction 설정

- 발급 직후 "URL restrictions" 섹션에 운영 도메인 추가
  - `<운영 도메인>/*`
  - `<preview 환경 와일드카드>/*`
  - 로컬 개발용은 별도 dev 토큰 사용 권장 (이 절차 외)
- restriction 미설정 토큰은 발급 24시간 이내 폐기 권장 (대시보드 자동 경고)

### 3) 환경 변수 갱신

- 배포 플랫폼 대시보드 → Project Settings → Environment Variables
- `NEXT_PUBLIC_MAPBOX_TOKEN` 값을 신규 토큰으로 교체
- `NEXT_PUBLIC_MAPBOX_STYLE_URL`은 변경 없음 (스타일 URL은 토큰 독립)
- production / preview / development 환경 각각에 적용

### 4) 배포 트리거

- main 브랜치 재배포 (Vercel: Deployments → 최신 → Redeploy)
- 배포 완료 후 운영 도메인에서 지도 렌더링 확인
- (선택) Mapbox 대시보드 "Statistics"에서 신규 토큰 호출량 증가 + 구 토큰 호출량 감소 확인

### 5) 구 토큰 폐기

- 신규 토큰 정상 동작 24시간 모니터링 후, 구 토큰 "Delete" 처리
- 토큰 식별: 생성일자 기반 (토큰명 규칙 활용)

## 롤백

- 신규 토큰 적용 후 401/403 발생 시 환경 변수를 구 토큰으로 즉시 되돌리고 재배포
- 구 토큰을 폐기하지 않은 상태에서만 가능 (5단계 전이 안전)

## 관련 문서

- [status.md](./status.md)
- [mapbox-env.md](./mapbox-env.md) — Mapbox 환경변수 형식·사용처·설정 흐름 가이드
