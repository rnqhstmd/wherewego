'use client';

import { useEffect, useState, type CSSProperties } from 'react';
import { NotificationItem } from './NotificationItem';
import { NotificationPinList } from './NotificationPinList';
import { colors, fonts } from '@/lib/design/tokens';
import type {
  NotificationDetail,
  NotificationItem as NotificationItemType,
  NotificationPinItem,
} from '@/lib/notifications/types';

interface NotificationPanelProps {
  items: NotificationItemType[];
  isOpen: boolean;
  onClose: () => void;
  onSelectPin: (pin: NotificationPinItem) => void;
  loadDetail: (notificationId: number) => Promise<NotificationDetail>;
  variant?: 'mobile' | 'desktop';
}

/**
 * 알림 목록 패널 — 모바일은 bottom sheet, 데스크탑은 좌측 floating side panel.
 *
 * <p>항목 클릭 시 상세를 로드하여 핀 목록을 보여주며, 헤더의 "← 목록" 버튼으로 복귀한다.
 * 패널이 닫히면 상세 상태도 함께 리셋된다. 빈 상태는 FR-20 카피("아직 알림이 없어요")로 노출.</p>
 *
 * <p>기존 Sheet/SidePanel 컨테이너와 룩을 맞추되, 본 컴포넌트는 헤더/뒤로가기 UX 가
 * 자체적으로 필요해 inline 스타일로 floating 카드를 직접 구성한다.</p>
 */
export function NotificationPanel({
  items,
  isOpen,
  onClose,
  onSelectPin,
  loadDetail,
  variant = 'mobile',
}: NotificationPanelProps) {
  const [activeDetail, setActiveDetail] = useState<NotificationDetail | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!isOpen) {
      setActiveDetail(null);
    }
  }, [isOpen]);

  if (!isOpen) return null;

  async function handleSelectItem(item: NotificationItemType) {
    setLoading(true);
    try {
      const detail = await loadDetail(item.id);
      setActiveDetail(detail);
    } finally {
      setLoading(false);
    }
  }

  function handleSelectPin(pin: NotificationPinItem) {
    onSelectPin(pin);
    onClose();
  }

  const containerStyle: CSSProperties =
    variant === 'desktop'
      ? {
          position: 'fixed',
          top: 14,
          left: 66,
          // 알림함은 사용자 정보량이 많은 영역이라 화면 절반 정도의 길이를 기본으로 확보.
          // 최대 50개 fetch + 본문 자체 스크롤. 알림 0건 빈 상태에서도 50vh 유지하여 시각 안정.
          maxHeight: 'calc(100% - 28px)',
          minHeight: '50vh',
          width: 360,
          background: colors.panel,
          borderRadius: 20,
          boxShadow: `0 10px 28px ${colors.shadowMd}`,
          border: `1px solid ${colors.hairline}`,
          zIndex: 50,
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
          fontFamily: fonts.sans,
        }
      : {
          position: 'fixed',
          bottom: 0,
          left: 0,
          right: 0,
          minHeight: '50vh',
          maxHeight: '70vh',
          background: colors.panel,
          borderTopLeftRadius: 20,
          borderTopRightRadius: 20,
          boxShadow: `0 -10px 28px ${colors.shadowMd}`,
          zIndex: 50,
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
          fontFamily: fonts.sans,
        };

  return (
    <div style={containerStyle}>
      {/* Header */}
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          alignItems: 'center',
          padding: '14px 16px',
          borderBottom: `1px solid ${colors.hairline}`,
          flexShrink: 0,
        }}
      >
        <strong style={{ fontSize: 16, color: colors.ink }}>
          {activeDetail ? `${activeDetail.registeredByNickname}님의 알림` : '알림'}
        </strong>
        <button
          type="button"
          onClick={activeDetail ? () => setActiveDetail(null) : onClose}
          style={{
            background: 'transparent',
            border: 'none',
            cursor: 'pointer',
            fontSize: 14,
            color: colors.inkSoft,
            fontFamily: fonts.sans,
          }}
        >
          {activeDetail ? '← 목록' : '닫기'}
        </button>
      </div>

      {/* Body */}
      <div style={{ overflowY: 'auto', flex: 1 }}>
        {loading ? (
          <div style={{ padding: 16, color: colors.inkSoft, fontSize: 13 }}>
            불러오는 중...
          </div>
        ) : activeDetail ? (
          <NotificationPinList pins={activeDetail.pins} onSelectPin={handleSelectPin} />
        ) : items.length === 0 ? (
          <div
            style={{
              padding: 24,
              textAlign: 'center',
              color: colors.inkSoft,
              fontSize: 13,
            }}
          >
            아직 알림이 없어요
          </div>
        ) : (
          items.map((item) => (
            <NotificationItem
              key={item.id}
              item={item}
              onClick={() => handleSelectItem(item)}
            />
          ))
        )}
      </div>
    </div>
  );
}
