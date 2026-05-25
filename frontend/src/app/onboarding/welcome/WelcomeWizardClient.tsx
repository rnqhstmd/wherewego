"use client";

import { Suspense, useCallback, useEffect, useMemo } from "react";
import { useRouter, useSearchParams } from "next/navigation";

import { colors, fonts } from "@/lib/design/tokens";
import type { OnboardingStatusResponse } from "@/lib/api/me-client";

import { Step1Group } from "./_steps/Step1Group";
import { Step2Invite } from "./_steps/Step2Invite";
import { Step3Bot } from "./_steps/Step3Bot";

const ONBOARDING_SEEN_KEY = "onboarding-welcome-seen";

interface WelcomeWizardClientProps {
  initialStatus: OnboardingStatusResponse;
}

/**
 * `/onboarding/welcome` 3단계 위저드 (Phase 11 PR-B AC-6).
 *
 * - 단계: 1) 그룹  2) 초대 링크 공유  3) 챗봇 연동
 * - URL `?step=1|2|3` 로 진행 상태 보존 (뒤로가기 정상 동작).
 * - 각 단계 "건너뛰기" 허용. 마지막 단계 완료/건너뛰기 시 `/map` 으로 이동.
 * - 진입 시 initialStatus 기반으로 이미 완료된 단계는 자동 다음 단계로 진행.
 * - localStorage `onboarding-welcome-seen=1` 마킹 (callback 의 자동 진입 분기는 별도, 직접 진입은 항상 허용).
 */
export function WelcomeWizardClient({ initialStatus }: WelcomeWizardClientProps) {
  return (
    <Suspense
      fallback={
        <div
          style={{
            width: "100%",
            minHeight: "100vh",
            background: colors.bg,
          }}
        />
      }
    >
      <WelcomeWizardInner initialStatus={initialStatus} />
    </Suspense>
  );
}

function WelcomeWizardInner({ initialStatus }: WelcomeWizardClientProps) {
  const router = useRouter();
  const searchParams = useSearchParams();

  // 진입 마킹 — 추후 callback 분기에서 이 flag 로 강제 진입 여부 결정.
  useEffect(() => {
    try {
      window.localStorage.setItem(ONBOARDING_SEEN_KEY, "1");
    } catch {
      // 사파리 시크릿 모드 등에서 localStorage 실패해도 위저드 자체 동작은 유지.
    }
  }, []);

  const stepParam = searchParams.get("step");
  const step = useMemo<1 | 2 | 3>(() => {
    if (stepParam === "2") return 2;
    if (stepParam === "3") return 3;
    return 1;
  }, [stepParam]);

  const goStep = useCallback(
    (next: 1 | 2 | 3) => {
      const sp = new URLSearchParams(searchParams.toString());
      sp.set("step", String(next));
      router.replace(`/onboarding/welcome?${sp.toString()}`);
    },
    [router, searchParams],
  );

  const finish = useCallback(() => {
    router.replace("/map");
  }, [router]);

  // 단계 진입 시 자동 skip 로직.
  useEffect(() => {
    if (step === 1 && initialStatus.hasActiveGroup) {
      // 이미 그룹 보유 — 짝꿍 없으면 초대, 둘 다 있으면 챗봇 또는 /map.
      if (initialStatus.activeGroupMemberCount < 2) {
        goStep(2);
      } else if (!initialStatus.hasBotMapping) {
        goStep(3);
      } else {
        finish();
      }
      return;
    }
    if (step === 2 && initialStatus.activeGroupMemberCount >= 2) {
      // 짝꿍 합류 완료 — 챗봇 또는 /map.
      if (!initialStatus.hasBotMapping) {
        goStep(3);
      } else {
        finish();
      }
      return;
    }
    if (step === 3 && initialStatus.hasBotMapping) {
      finish();
    }
  }, [step, initialStatus, goStep, finish]);

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
          padding: "32px 24px 32px",
          display: "flex",
          flexDirection: "column",
          boxSizing: "border-box",
        }}
      >
        <StepProgress current={step} />

        <div style={{ marginTop: 32, flex: 1 }}>
          {step === 1 ? (
            <Step1Group onSkip={() => goStep(2)} />
          ) : null}
          {step === 2 ? (
            <Step2Invite
              onCompleted={() => goStep(3)}
              onSkip={() => goStep(3)}
            />
          ) : null}
          {step === 3 ? (
            <Step3Bot onCompleted={finish} onSkip={finish} />
          ) : null}
        </div>
      </div>
    </div>
  );
}

function StepProgress({ current }: { current: 1 | 2 | 3 }) {
  const labels: Array<{ idx: 1 | 2 | 3; label: string }> = [
    { idx: 1, label: "그룹" },
    { idx: 2, label: "초대" },
    { idx: 3, label: "챗봇" },
  ];
  return (
    <div
      style={{
        display: "flex",
        gap: 8,
        alignItems: "center",
        paddingTop: 16,
      }}
      aria-label={`온보딩 단계 ${current}/3`}
    >
      {labels.map(({ idx, label }) => {
        const active = idx === current;
        const done = idx < current;
        return (
          <div
            key={idx}
            style={{
              flex: 1,
              display: "flex",
              flexDirection: "column",
              gap: 6,
            }}
          >
            <div
              style={{
                height: 4,
                borderRadius: 2,
                background: active || done ? colors.cta : colors.hairline,
              }}
            />
            <div
              style={{
                fontSize: 11,
                color: active ? colors.ink : colors.inkFaint,
                fontWeight: active ? 700 : 500,
              }}
            >
              {idx}. {label}
            </div>
          </div>
        );
      })}
    </div>
  );
}
