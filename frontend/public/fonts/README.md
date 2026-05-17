# Local fonts

self-host 폰트 바이너리 디렉토리.

## 현황

- `PretendardVariable.woff2` (~2 MB) — Pretendard Variable v1.3.9.
  `layout.tsx` 의 `next/font/local` 로 `--font-sans` 변수 주입.

## 폰트 출처

- Pretendard: https://github.com/orioncactus/pretendard (OFL-1.1)
  - 갱신: jsDelivr 미러에서 다운로드 가능.
    `curl -sL -o frontend/public/fonts/PretendardVariable.woff2 https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/packages/pretendard/dist/web/variable/woff2/PretendardVariable.woff2`
  - 새 메이저 릴리스 시 `@vX.Y.Z` 태그만 교체.
- 그 외 (`Noto Serif KR`, `Gowun Batang`, `JetBrains Mono`): `next/font/google` 로딩, 별도 자산 없음.

## 운영 메모

self-host 전환으로 jsDelivr 외부 의존성을 제거했다. 새 폰트를 추가하면
`layout.tsx` 의 `next/font/local` 블록과 본 README 의 폰트 출처 표를 함께 갱신할 것.
