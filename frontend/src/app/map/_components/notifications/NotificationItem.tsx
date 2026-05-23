'use client';

import type { NotificationItem as NotificationItemType } from '@/lib/notifications/types';
import { colors, fonts } from '@/lib/design/tokens';

interface NotificationItemProps {
  item: NotificationItemType;
  onClick: () => void;
  currentUserId: number;
}

/**
 * 알림 1건 카드. 클릭 시 상세 로드 → onSelect.
 *
 * <p>읽지 않은 알림은 옅은 CTA 배경으로 강조.</p>
 * <p>Row 1: 행위자명 + 타입칩 + 시간, Row 2: 장소 요약.</p>
 */
export function NotificationItem({ item, onClick, currentUserId }: NotificationItemProps) {
  const isSelf = item.registeredBy === currentUserId;
  const actorLabel = isSelf ? '나' : item.registeredByNickname;
  const placeSummary =
    item.totalPinCount <= 1
      ? item.firstPlaceName
      : `${item.firstPlaceName} 외 ${item.totalPinCount - 1}곳`;

  return (
    <button
      type="button"
      onClick={onClick}
      style={{
        display: 'block',
        width: '100%',
        padding: '12px 16px',
        textAlign: 'left',
        background: item.readAt ? 'transparent' : 'rgba(196,98,45,0.06)',
        border: 'none',
        borderBottom: `1px solid ${colors.hairline}`,
        cursor: 'pointer',
        fontFamily: fonts.sans,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', marginBottom: 5 }}>
        <span style={{ fontSize: 13, color: colors.ink, fontWeight: 600 }}>{actorLabel}</span>
        <span style={{ marginLeft: 'auto', fontSize: 11, color: colors.inkSoft, fontFamily: fonts.mono }}>
          {formatTime(item.createdAt)}
        </span>
      </div>
      <div style={{ fontSize: 14, color: colors.ink, fontWeight: 500 }}>
        {placeSummary}
      </div>
    </button>
  );
}

function formatTime(iso: string): string {
  try {
    const d = new Date(iso);
    const now = Date.now();
    const diffMin = Math.floor((now - d.getTime()) / 60_000);
    if (diffMin < 1) return '방금 전';
    if (diffMin < 60) return `${diffMin}분 전`;
    const diffHour = Math.floor(diffMin / 60);
    if (diffHour < 24) return `${diffHour}시간 전`;
    const diffDay = Math.floor(diffHour / 24);
    if (diffDay < 7) return `${diffDay}일 전`;
    return d.toLocaleDateString('ko-KR');
  } catch {
    return '';
  }
}
