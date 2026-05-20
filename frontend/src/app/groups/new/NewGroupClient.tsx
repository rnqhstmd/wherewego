"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { BtnSub } from "@/components/ui/BtnSub";
import { BackButton } from "@/components/ui/BackButton";
import { createGroup } from "@/lib/api/group-client";
import { colors, fonts } from "@/lib/design/tokens";

/**
 * 그룹 생성 입력 화면 (디자인 시스템 일관 유지: Gowun Batang 헤딩 + Pretendard 본문).
 * 성공 시 `/groups` 로 이동하여 새 그룹을 활성 그룹으로 표시.
 */
export function NewGroupClient() {
  const router = useRouter();
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const trimmed = name.trim();
  const isValid = trimmed.length >= 1 && trimmed.length <= 20;
  const canSubmit = isValid && !submitting;

  const handleSubmit = async () => {
    if (!canSubmit) return;
    setError(null);
    setSubmitting(true);
    try {
      await createGroup(trimmed);
      router.replace("/groups");
      router.refresh();
    } catch (e) {
      const message =
        e instanceof Error && e.message
          ? e.message
          : "그룹 생성에 실패했어요. 잠시 후 다시 시도해 주세요.";
      setError(message);
      setSubmitting(false);
    }
  };

  return (
    <div
      style={{
        background: colors.bg,
        minHeight: "100vh",
        fontFamily: fonts.sans,
        display: "flex",
        justifyContent: "center",
        boxSizing: "border-box",
      }}
    >
      <div
        style={{
          width: "100%",
          maxWidth: 460,
          padding: "80px 32px 32px",
          display: "flex",
          flexDirection: "column",
          boxSizing: "border-box",
        }}
      >
      <BackButton onClick={() => router.back()} />
      <div
        style={{
          fontFamily: fonts.emo,
          fontSize: 32,
          fontWeight: 700,
          color: colors.ink,
          lineHeight: 1.3,
          letterSpacing: -1,
          whiteSpace: "pre-wrap",
        }}
      >
        {"새 지도를 만들어요\n어떤 이름이 좋을까요?"}
      </div>
      <div
        style={{
          fontSize: 14,
          color: colors.inkSoft,
          marginTop: 12,
          lineHeight: 1.5,
        }}
      >
        함께하는 사람들과 공유할 지도의 이름이에요.
      </div>

      <div
        style={{
          marginTop: 40,
          borderBottom: `2px solid ${colors.cta}`,
          padding: "0 0 8px 0",
        }}
      >
        <input
          type="text"
          value={name}
          onChange={(e) => setName(e.target.value.slice(0, 20))}
          placeholder="예: 우리집 데이트 지도"
          maxLength={20}
          autoFocus
          style={{
            width: "100%",
            border: "none",
            background: "transparent",
            fontFamily: fonts.emo,
            fontSize: 22,
            fontWeight: 700,
            color: colors.ink,
            outline: "none",
            padding: 0,
          }}
        />
      </div>
      <div
        style={{
          fontSize: 12,
          color: isValid || name.length === 0 ? colors.inkSoft : colors.cta,
          marginTop: 8,
        }}
      >
        1~20자
      </div>

      {error && (
        <div
          style={{
            fontSize: 13,
            color: colors.cta,
            marginTop: 12,
          }}
          role="alert"
        >
          {error}
        </div>
      )}

      <div style={{ flex: 1 }} />

      <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
        <BtnPrimary
          onClick={handleSubmit}
          disabled={!canSubmit}
          style={{
            width: "100%",
            padding: "14px 0",
            fontSize: 15,
          }}
        >
          {submitting ? "만드는 중..." : "만들기"}
        </BtnPrimary>
        <BtnSub
          onClick={() => router.back()}
          style={{
            width: "100%",
            padding: "13px 0",
            fontSize: 14,
          }}
        >
          취소
        </BtnSub>
      </div>
      </div>
    </div>
  );
}
