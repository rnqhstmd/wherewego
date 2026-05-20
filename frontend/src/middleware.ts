import { NextResponse, type NextRequest } from "next/server";
import { GATE_COOKIE_NAME, verifyGateCookie } from "@/lib/auth/gate";

/**
 * 2인 비공개 서비스 게이트.
 * `/gate` 쿠키가 유효하지 않으면 모든 라우트에서 `/gate?returnUrl=...`로 redirect.
 * 게이트 라우트 자체와 정적 자산, 게이트 API는 통과시킨다.
 */
export async function middleware(req: NextRequest) {
  const { pathname, search } = req.nextUrl;

  // 게이트 자체 + 게이트 API는 항상 통과 (그래야 인증할 길이 열림)
  if (pathname === "/gate" || pathname.startsWith("/api/auth/gate")) {
    return NextResponse.next();
  }

  const cookieValue = req.cookies.get(GATE_COOKIE_NAME)?.value;
  if (await verifyGateCookie(cookieValue)) {
    return NextResponse.next();
  }

  const returnUrl = pathname + search;
  const gateUrl = new URL("/gate", req.url);
  if (returnUrl && returnUrl !== "/") {
    gateUrl.searchParams.set("returnUrl", returnUrl);
  }
  return NextResponse.redirect(gateUrl);
}

export const config = {
  matcher: [
    "/((?!_next/static|_next/image|favicon.ico|robots.txt|.*\\.(?:svg|png|jpg|jpeg|gif|webp|woff2?|ttf|otf)).*)",
  ],
};
