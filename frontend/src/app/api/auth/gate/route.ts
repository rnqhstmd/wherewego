import { NextResponse, type NextRequest } from "next/server";
import {
  GATE_COOKIE_MAX_AGE_SECONDS,
  GATE_COOKIE_NAME,
  computeExpectedGateCookie,
  verifyCredentials,
} from "@/lib/auth/gate";

/**
 * Gate 쿠키 제거. 로그아웃 시 카카오 logout과 같이 호출하여 게이트도 해제한다.
 * 다음 접근 시 다시 ID/PW 입력을 요구한다.
 */
export async function DELETE() {
  const res = NextResponse.json({ ok: true });
  res.cookies.set(GATE_COOKIE_NAME, "", {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    path: "/",
    maxAge: 0,
  });
  return res;
}

/**
 * Gate 자격증명 검증 + 쿠키 발급.
 * 일치하면 HMAC 서명된 쿠키를 발급해서 middleware가 통과시킨다.
 */
export async function POST(req: NextRequest) {
  let body: { user?: string; password?: string };
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ ok: false, message: "잘못된 요청" }, { status: 400 });
  }
  const user = String(body.user ?? "").trim();
  const password = String(body.password ?? "");
  if (!user || !password) {
    return NextResponse.json(
      { ok: false, message: "아이디/비밀번호를 입력해 주세요" },
      { status: 400 },
    );
  }
  if (!(await verifyCredentials(user, password))) {
    return NextResponse.json(
      { ok: false, message: "아이디 또는 비밀번호가 올바르지 않습니다" },
      { status: 401 },
    );
  }
  const value = await computeExpectedGateCookie();
  if (!value) {
    return NextResponse.json(
      { ok: false, message: "서버 설정 오류" },
      { status: 500 },
    );
  }
  const res = NextResponse.json({ ok: true });
  res.cookies.set(GATE_COOKIE_NAME, value, {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    path: "/",
    maxAge: GATE_COOKIE_MAX_AGE_SECONDS,
  });
  return res;
}
