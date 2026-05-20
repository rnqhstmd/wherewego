# Mapbox 환경변수 가이드

> 본 문서는 Wherewego 프론트엔드(`frontend/`)에서 사용하는 Mapbox 관련 환경변수 참고 가이드다.
> 실제 값은 운영 환경(배포 플랫폼 대시보드) 또는 로컬 개발 환경(`frontend/.env.local`, git tracked X)에서 설정한다.
> 토큰 회전·발급 절차는 [mapbox-token-sop.md](./mapbox-token-sop.md) 참조.

---

## 환경변수 목록

### `NEXT_PUBLIC_MAPBOX_TOKEN` (필수)

- **용도**: Mapbox 공개 액세스 토큰. 브라우저 번들에 포함되어 클라이언트에서 지도 타일/스타일/폰트를 로드할 때 사용한다.
- **사용처**: `frontend/src/app/map/page.tsx`, `frontend/src/app/map/_components/MapboxView.tsx`, `frontend/src/app/map/_lib/reverseGeocode.ts`
- **형식**: `pk.eyJ1...` 로 시작하는 Mapbox public token
- **필수 설정**: 발급 즉시 Mapbox 대시보드에서 **URL Restriction**을 운영 도메인으로 설정해야 한다 (BR-6, mapbox-token-sop.md §2).
- **로컬 개발**: 별도 개발용 토큰 발급 후 `frontend/.env.local`에 기재
- **운영**: 배포 플랫폼(Vercel 등) Project Settings → Environment Variables에 등록. production / preview / development 환경 각각 설정.

```env
# 예시 (실제 값은 git에 커밋하지 말 것)
NEXT_PUBLIC_MAPBOX_TOKEN=pk.eyJ1Ijoi...
```

### `NEXT_PUBLIC_MAPBOX_STYLE_URL` (선택)

- **용도**: 커스텀 지도 스타일 URL. Mapbox Studio에서 디자인 토큰 색상으로 커스텀 스타일을 만든 후 사용.
- **사용처**: `frontend/src/app/map/_components/MapboxView.tsx`
- **형식**: `mapbox://styles/<username>/<style-id>` 형태의 Mapbox Studio style URL
- **미설정 시**: 기본 스타일 `mapbox://styles/mapbox/light-v11` 사용
- **디자인 토큰 참조**: 커스텀 스타일은 `frontend/src/lib/design/tokens.ts`의 mapBg(#EAE4D4), mapWater(#D4E8F0) 등을 기반으로 작성한다.

```env
# 예시 (미설정 시 기본 스타일 사용)
NEXT_PUBLIC_MAPBOX_STYLE_URL=mapbox://styles/myaccount/abc123
```

---

## 설정 흐름

### 로컬 개발자

1. `frontend/.env.local` 파일을 새로 생성 (.gitignore에 의해 자동 무시됨).
2. 위 두 환경변수를 본인의 개발용 Mapbox 토큰 값으로 기재.
3. `npm run dev` 재시작.

### 운영자 (배포)

1. Vercel 등 배포 플랫폼 Project Settings → Environment Variables 접근.
2. `NEXT_PUBLIC_MAPBOX_TOKEN`, `NEXT_PUBLIC_MAPBOX_STYLE_URL` 등록 (production / preview / development 각각).
3. 토큰 발급 시 URL Restriction을 반드시 설정 (mapbox-token-sop.md §2).
4. 재배포 트리거 후 운영 도메인에서 지도 렌더링 확인.

---

## 관련 문서

- [mapbox-token-sop.md](./mapbox-token-sop.md) — 토큰 회전·발급·폐기 SOP
- [status.md](./status.md) — Map 도메인 상태
- [architecture.md](./architecture.md) — Map 도메인 아키텍처
