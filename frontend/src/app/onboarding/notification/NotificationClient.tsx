"use client";

import { useRouter } from "next/navigation";
import { PermissionDialog } from "@/components/ui/PermissionDialog";
import { IconBell } from "@/components/icons";
import { notifAsked } from "@/lib/storage/local-flags";
import { colors } from "@/lib/design/tokens";

/**
 * /onboarding/notification — 알림 권한 요청 클라이언트 컴포넌트.
 * screens-basic.jsx::NotifPermMobile + PermissionDialog 재사용.
 *
 * - "허용" / "다음에" 어느 쪽을 누르더라도 notifAsked.set(true) 후 /map으로.
 * - Notification API 미지원 환경에서는 즉시 /map.
 */
export function NotificationClient() {
  const router = useRouter();

  const goMap = () => {
    notifAsked.set(true);
    router.replace("/map");
  };

  const onAllow = async () => {
    if (typeof window === "undefined" || !("Notification" in window)) {
      goMap();
      return;
    }
    if (Notification.permission === "denied") {
      // 차단 상태는 시스템 설정에서만 변경 가능. 가벼운 안내 후 진행.
      try {
        window.alert("브라우저 설정에서 알림을 허용해 주세요");
      } catch {
        // alert 미지원 환경(예: 일부 SSR/테스트)에서는 무시
      }
      goMap();
      return;
    }
    try {
      await Notification.requestPermission();
    } catch {
      // 권한 요청 자체가 실패해도 흐름은 진행
    }
    goMap();
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
        icon={<IconBell size={28} color={colors.cta} />}
        title="알림 받아볼래요?"
        description={"함께하는 사람이 핀을 추가하면\n알려드려요"}
        primaryLabel="알림 허용"
        secondaryLabel="다음에"
        layout="vertical"
        onPrimary={onAllow}
        onSecondary={goMap}
      />
    </div>
  );
}
