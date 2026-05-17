# Local fonts

이 디렉토리는 self-host 폰트 바이너리를 위한 자리이다.

## Phase 6 현황

Phase 6 배치 1 에서는 Pretendard 를 CDN 으로 로드한다
(`frontend/src/app/layout.tsx` 의 `<link rel="stylesheet" .../>`).
self-host 전환은 후속 작업으로 분리되어 있으므로 본 디렉토리는 비어 있다.

## Pretendard self-host 전환 방법 (후속 작업)

1. https://github.com/orioncactus/pretendard/releases 에서 최신
   `PretendardVariable.woff2` 다운로드.
2. 이 디렉토리(`frontend/public/fonts/PretendardVariable.woff2`)에 배치.
3. `frontend/src/app/layout.tsx` 의 CDN `<link>` 를 제거하고
   `next/font/local` 로 교체:

   ```ts
   import localFont from "next/font/local";

   const sans = localFont({
     src: "../../public/fonts/PretendardVariable.woff2",
     variable: "--font-sans",
     display: "swap",
   });
   ```

4. `<html>` className 에 `${sans.variable}` 추가.
5. `frontend/src/app/globals.css` 의 `:root --font-sans` 폴백 정의를 제거
   (next/font 가 변수를 주입함).
