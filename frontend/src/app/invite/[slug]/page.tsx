import type { Metadata } from "next";

import { ApiError } from "@/lib/api/http";
import { getInviteLinkPreviewServer } from "@/lib/api/invite-server";
import type { InviteLinkPreviewResponse } from "@/lib/api/group-client";

import { InvitePreviewClient } from "./InvitePreviewClient";
import { InviteExpiredState } from "./InviteExpiredState";

export const dynamic = "force-dynamic";

interface InvitePageProps {
  params: Promise<{ slug: string }>;
}

async function loadPreview(
  slug: string,
): Promise<InviteLinkPreviewResponse | null> {
  try {
    return await getInviteLinkPreviewServer(slug);
  } catch (e) {
    if (e instanceof ApiError && (e.status === 404 || e.status === 410)) {
      return null;
    }
    if (e instanceof ApiError && e.status === 429) {
      return null;
    }
    throw e;
  }
}

/**
 * 카톡 공유 시 OG 미리보기 — 그룹명/초대자 닉네임을 표시.
 * 만료/소진/없음일 때는 일반 메타로 폴백한다.
 */
export async function generateMetadata(
  { params }: InvitePageProps,
): Promise<Metadata> {
  const { slug } = await params;
  const preview = await loadPreview(slug);
  if (!preview) {
    return {
      title: "초대 링크가 만료됐어요 · wherewego",
      description: "짝꿍에게 새 초대 링크를 받아주세요.",
    };
  }
  const title = `${preview.inviterNickname}님이 '${preview.groupName}'에 초대했어요`;
  const description = "wherewego에서 함께 지도를 만들어요.";
  return {
    title,
    description,
    openGraph: {
      title,
      description,
      type: "website",
    },
    twitter: {
      card: "summary",
      title,
      description,
    },
  };
}

/**
 * `/invite/[slug]` — 단축 슬러그 기반 초대 링크 진입점.
 *
 * - 유효한 slug: 그룹명/초대자 닉네임 미리보기 + "합류하기" CTA
 *   (미로그인 시 `/login?returnUrl=/invite/{slug}` 로 보낸 뒤 복귀하여 합류)
 * - 만료/소진/없음: InviteExpiredState 컴포넌트
 * - 기존 `/onboarding/invite-code?token=...` 라우트는 BC 로 그대로 유지된다.
 */
export default async function InvitePage({ params }: InvitePageProps) {
  const { slug } = await params;
  const preview = await loadPreview(slug);
  if (!preview) {
    return <InviteExpiredState />;
  }
  return <InvitePreviewClient slug={slug} preview={preview} />;
}
