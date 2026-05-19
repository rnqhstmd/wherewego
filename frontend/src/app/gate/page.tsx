"use client";

import { useState, useTransition } from "react";
import { useRouter, useSearchParams } from "next/navigation";

/**
 * 2인 비공개 서비스 게이트 — 단일 초대 코드(6자리) 입력 화면.
 * 코드 통과 시 httpOnly 쿠키 발급 → returnUrl로 redirect → 카카오 로그인 흐름 진행.
 * 통과 전엔 모든 라우트가 middleware에 의해 이 페이지로 강제 redirect된다.
 */
export default function GatePage() {
  const router = useRouter();
  const params = useSearchParams();
  const returnUrl = params.get("returnUrl") ?? "/";
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    // 숫자만, 6자리 제한
    const next = e.target.value.replace(/\D/g, "").slice(0, 6);
    setCode(next);
    if (error) setError(null);
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (code.length !== 6) {
      setError("6자리 코드를 입력해 주세요");
      return;
    }
    setError(null);
    startTransition(async () => {
      try {
        const res = await fetch("/api/auth/gate", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ code }),
        });
        if (res.ok) {
          router.replace(returnUrl);
          router.refresh();
          return;
        }
        const data = await res.json().catch(() => ({}));
        setError(data?.message ?? "코드 확인에 실패했습니다");
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
          padding: "36px 28px 32px",
          boxShadow: "0 10px 28px rgba(0,0,0,0.08)",
          display: "flex",
          flexDirection: "column",
          gap: 18,
        }}
      >
        <div style={{ textAlign: "center", marginBottom: 4 }}>
          <div
            style={{
              fontSize: 26,
              fontWeight: 700,
              color: "#1A1A2E",
              fontFamily: "var(--font-emo), 'Gowun Batang', serif",
              letterSpacing: -0.5,
            }}
          >
            우리가 갈 지도
          </div>
          <div
            style={{
              fontSize: 13,
              color: "#8B8B9E",
              marginTop: 8,
              lineHeight: 1.5,
            }}
          >
            초대받은 분만 입장할 수 있어요
            <br />
            6자리 코드를 입력해 주세요
          </div>
        </div>
        <label style={fieldLabelStyle}>
          초대 코드
          <input
            type="text"
            inputMode="numeric"
            pattern="[0-9]*"
            value={code}
            onChange={handleChange}
            autoFocus
            autoComplete="one-time-code"
            maxLength={6}
            disabled={pending}
            placeholder="● ● ● ● ● ●"
            style={{
              ...inputStyle,
              textAlign: "center",
              letterSpacing: 8,
              fontSize: 22,
              fontWeight: 700,
              height: 52,
              fontFamily: "var(--font-mono), 'Menlo', monospace",
            }}
          />
        </label>
        {error && (
          <div
            role="alert"
            style={{ fontSize: 12, color: "#E05A5A", marginTop: -8, textAlign: "center" }}
          >
            {error}
          </div>
        )}
        <button
          type="submit"
          disabled={pending || code.length !== 6}
          style={{
            marginTop: 6,
            height: 46,
            borderRadius: 10,
            border: "none",
            background: pending || code.length !== 6 ? "#C5C5D0" : "#C4622D",
            color: "#fff",
            fontSize: 15,
            fontWeight: 700,
            cursor: pending || code.length !== 6 ? "not-allowed" : "pointer",
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
  gap: 8,
  fontSize: 12,
  fontWeight: 600,
  color: "#8B8B9E",
};

const inputStyle: React.CSSProperties = {
  padding: "0 12px",
  borderRadius: 10,
  border: "1px solid #E8E4DE",
  background: "#fff",
  color: "#1A1A2E",
  outline: "none",
};
