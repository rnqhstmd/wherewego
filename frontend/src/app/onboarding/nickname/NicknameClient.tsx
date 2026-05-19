"use client";

import {
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type CompositionEvent,
} from "react";
import { useRouter } from "next/navigation";
import { BtnPrimary } from "@/components/ui/BtnPrimary";
import { updateNickname } from "@/lib/api/user";
import { nicknameSet } from "@/lib/storage/local-flags";
import {
  sanitizeNickname,
  validateNickname,
} from "@/lib/validation/nickname";
import { colors, fonts } from "@/lib/design/tokens";

interface NicknameClientProps {
  initialNickname: string;
}

/**
 * Screen 0b — 닉네임 설정 (screens-login.jsx::Screen0bNickname 1:1).
 *
 * - 입력값은 sanitizeNickname으로 한글/영문/숫자만 허용, 12자 절단.
 * - validateNickname.valid 여부로 다음 버튼 활성화.
 * - 저장 성공: nicknameSet.set(true) → /onboarding/group-start.
 */
export function NicknameClient({ initialNickname }: NicknameClientProps) {
  const router = useRouter();
  const [nickname, setNickname] = useState<string>(
    sanitizeNickname(initialNickname),
  );
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const validation = useMemo(() => validateNickname(nickname), [nickname]);
  const canSubmit = validation.valid && !submitting;

  // 한글 IME composition 중에는 자모만 들어와서 sanitize가 즉시 제거하면 입력이 끊긴다.
  // composition이 끝난 시점(자모 → 완성 글자)에 한 번만 sanitize한다.
  const composingRef = useRef(false);

  const onChange = (e: ChangeEvent<HTMLInputElement>) => {
    if (composingRef.current) {
      setNickname(e.target.value.slice(0, 12));
    } else {
      setNickname(sanitizeNickname(e.target.value));
    }
    if (error) setError(null);
  };

  const onCompositionStart = () => {
    composingRef.current = true;
  };

  const onCompositionEnd = (e: CompositionEvent<HTMLInputElement>) => {
    composingRef.current = false;
    setNickname(sanitizeNickname(e.currentTarget.value));
  };

  const onSubmit = async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    try {
      await updateNickname(nickname);
      nicknameSet.set(true);
      router.replace("/onboarding/group-start");
    } catch {
      setSubmitting(false);
      setError("저장에 실패했어요. 잠시 후 다시 시도해 주세요");
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
      {/* Heading */}
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
        {"반가워요\n이름을 알려주세요"}
      </div>

      {/* Sub */}
      <div
        style={{
          marginTop: 12,
          fontSize: 14,
          color: colors.inkSoft,
          lineHeight: 1.6,
        }}
      >
        함께하는 사람에게 보여질 이름이에요
      </div>

      {/* Input */}
      <div
        style={{
          marginTop: 40,
          borderBottom: `2px solid ${colors.cta}`,
          padding: "0 0 8px 0",
        }}
      >
        <input
          type="text"
          value={nickname}
          onChange={onChange}
          onCompositionStart={onCompositionStart}
          onCompositionEnd={onCompositionEnd}
          maxLength={12}
          autoFocus
          aria-label="닉네임"
          style={{
            width: "100%",
            border: "none",
            background: "transparent",
            fontFamily: fonts.emo,
            fontSize: 24,
            fontWeight: 700,
            color: colors.ink,
            outline: "none",
            padding: 0,
          }}
        />
      </div>

      {/* Hint */}
      <div
        style={{
          marginTop: 8,
          fontSize: 12,
          color: validation.valid ? colors.inkSoft : colors.cta,
        }}
      >
        한글, 영문, 숫자 2~12자
      </div>

      {/* Error */}
      {error ? (
        <div
          role="alert"
          style={{
            marginTop: 12,
            fontSize: 13,
            color: colors.cta,
          }}
        >
          {error}
        </div>
      ) : null}

      <div style={{ flex: 1 }} />

      <BtnPrimary
        onClick={onSubmit}
        disabled={!canSubmit}
        style={{ width: "100%", padding: "14px 0", fontSize: 15 }}
      >
        다음
      </BtnPrimary>
    </div>
    </div>
  );
}
