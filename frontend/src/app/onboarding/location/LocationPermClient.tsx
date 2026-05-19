"use client";

import { useRouter, useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { PermissionDialog } from "@/components/ui/PermissionDialog";
import { IconLocation } from "@/components/icons";
import { colors } from "@/lib/design/tokens";
import { locationAsked } from "@/lib/storage/local-flags";

/**
 * 카카오 로그인 직후 위치 권한 요청.
 * - 허용: navigator.geolocation으로 현재 위치 1회 요청 (실제 위치값은 사용하지 않고 권한 prompt만 트리거)
 * - 나중에: 권한 요청 없이 통과
 * - 어느 쪽이든 locationAsked 플래그 셋팅 후 next 또는 기본 경로로 이동
 *
 * next 쿼리 파라미터로 다음 목적지(예: /onboarding/nickname, /map)를 전달.
 * 미지정 시 기본 경로는 /를 사용해 server-side에서 그룹/닉네임 상태에 따라 재분기.
 */
function LocationPermInner() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const next = searchParams.get("next") ?? "/";

  const proceed = () => {
    locationAsked.set(true);
    router.replace(next);
  };

  const onAllow = () => {
    if (typeof window === "undefined" || !("geolocation" in navigator)) {
      proceed();
      return;
    }
    if (
      "permissions" in navigator &&
      typeof navigator.permissions?.query === "function"
    ) {
      navigator.permissions.query({ name: "geolocation" }).catch(() => {});
    }
    navigator.geolocation.getCurrentPosition(
      () => proceed(),
      () => proceed(),
      { enableHighAccuracy: false, timeout: 8000, maximumAge: 60_000 },
    );
  };

  return (
    <div
      style={{
        width: "100%",
        minHeight: "100vh",
        background: colors.bg,
        position: "relative",
        overflow: "hidden",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
      }}
    >
      <PermissionDialog
        icon={<IconLocation size={30} color={colors.cta} />}
        title="위치를 알려주세요"
        description={`근처에 어떤 핀이 있는지\n랜덤 뽑기에서 활용할 거예요`}
        primaryLabel="위치 사용 허용"
        secondaryLabel="나중에"
        layout="vertical"
        iconBgOpacity={0.15}
        onPrimary={onAllow}
        onSecondary={proceed}
      />
    </div>
  );
}

export function LocationPermClient() {
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
      <LocationPermInner />
    </Suspense>
  );
}
