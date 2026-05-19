"use client";

import { useState, useTransition } from "react";
import { useRouter, useSearchParams } from "next/navigation";

/**
 * 2인 비공개 게이트 화면.
 * 자격증명 통과 시 httpOnly 쿠키 발급 → returnUrl로 redirect.
 * 통과 전엔 모든 라우트가 middleware에 의해 이 페이지로 강제 redirect된다.
 */
export default function GatePage() {
  const router = useRouter();
  const params = useSearchParams();
  const returnUrl = params.get("returnUrl") ?? "/";
  const [user, setUser] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    startTransition(async () => {
      try {
        const res = await fetch("/api/auth/gate", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ user, password }),
        });
        if (res.ok) {
          router.replace(returnUrl);
          router.refresh();
          return;
        }
        const data = await res.json().catch(() => ({}));
        setError(data?.message ?? "로그인에 실패했습니다");
      } catch {
        setError("네트워크 오류가 발생했습니다");
      }
    });
  };

  return (
    <main
      style={{
        minHeight: "100dvh",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        background: "#FAF8F5",
        padding: 24,
      }}
    >
      <form
        onSubmit={handleSubmit}
        style={{
          width: "100%",
          maxWidth: 360,
          background: "#fff",
          borderRadius: 18,
          padding: "32px 28px",
          boxShadow: "0 10px 28px rgba(0,0,0,0.08)",
          display: "flex",
          flexDirection: "column",
          gap: 16,
        }}
      >
        <div style={{ textAlign: "center", marginBottom: 4 }}>
          <div
            style={{
              fontSize: 24,
              fontWeight: 700,
              color: "#1A1A2E",
              fontFamily: "var(--font-emo), 'Gowun Batang', serif",
            }}
          >
            우리가 갈 지도
          </div>
          <div
            style={{
              fontSize: 13,
              color: "#8B8B9E",
              marginTop: 6,
            }}
          >
            초대받은 분만 입장할 수 있어요
          </div>
        </div>
        <label style={fieldLabelStyle}>
          아이디
          <input
            type="text"
            value={user}
            onChange={(e) => setUser(e.target.value)}
            autoFocus
            autoComplete="username"
            disabled={pending}
            style={inputStyle}
          />
        </label>
        <label style={fieldLabelStyle}>
          비밀번호
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            disabled={pending}
            style={inputStyle}
          />
        </label>
        {error && (
          <div
            role="alert"
            style={{ fontSize: 12, color: "#E05A5A", marginTop: -4 }}
          >
            {error}
          </div>
        )}
        <button
          type="submit"
          disabled={pending || !user || !password}
          style={{
            marginTop: 6,
            height: 44,
            borderRadius: 10,
            border: "none",
            background: pending ? "#C5C5D0" : "#C4622D",
            color: "#fff",
            fontSize: 14,
            fontWeight: 700,
            cursor: pending ? "not-allowed" : "pointer",
          }}
        >
          {pending ? "확인 중..." : "입장"}
        </button>
      </form>
    </main>
  );
}

const fieldLabelStyle: React.CSSProperties = {
  display: "flex",
  flexDirection: "column",
  gap: 6,
  fontSize: 12,
  fontWeight: 600,
  color: "#8B8B9E",
};

const inputStyle: React.CSSProperties = {
  height: 40,
  padding: "0 12px",
  borderRadius: 8,
  border: "1px solid #E8E4DE",
  background: "#fff",
  fontSize: 14,
  color: "#1A1A2E",
  outline: "none",
};
