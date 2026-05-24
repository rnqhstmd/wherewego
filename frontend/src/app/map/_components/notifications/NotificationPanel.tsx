'use client';

import { useEffect, useRef, useState, type CSSProperties } from 'react';
import { NotificationItem } from './NotificationItem';
import { NotificationPinList } from './NotificationPinList';
import { colors, fonts } from '@/lib/design/tokens';
import type {
  NotificationDetail,
  NotificationItem as NotificationItemType,
  NotificationPinItem,
} from '@/lib/notifications/types';

function groupByDate(items: NotificationItemType[]): { label: string; items: NotificationItemType[] }[] {
  const now = new Date();
  const todayStart = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const yesterdayStart = new Date(todayStart);
  yesterdayStart.setDate(yesterdayStart.getDate() - 1);
  const weekStart = new Date(todayStart);
  weekStart.setDate(weekStart.getDate() - 7);

  const buckets: Record<string, NotificationItemType[]> = {};
  const order = ['오늘', '어제', '이번 주', '이전'];

  for (const item of items) {
    const d = new Date(item.createdAt);
    let label: string;
    if (d >= todayStart) label = '오늘';
    else if (d >= yesterdayStart) label = '어제';
    else if (d >= weekStart) label = '이번 주';
    else label = '이전';
    (buckets[label] ??= []).push(item);
  }

  return order.filter((l) => buckets[l]).map((l) => ({ label: l, items: buckets[l] }));
}

interface NotificationPanelProps {
  items: NotificationItemType[];
  isOpen: boolean;
  onClose: () => void;
  onSelectPin: (pin: NotificationPinItem) => void;
  loadDetail: (notificationId: number) => Promise<NotificationDetail>;
  variant?: 'mobile' | 'desktop';
  currentUserId: number;
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
  currentUserId,
}: NotificationPanelProps) {
  const [activeDetail, setActiveDetail] = useState<NotificationDetail | null>(null);
  const [selectedItem, setSelectedItem] = useState<NotificationItemType | null>(null);
  const [loading, setLoading] = useState(false);

  // 목록 스크롤 위치 보존 — 상세에서 목록 복귀 시 이전 위치로 복원.
  const bodyRef = useRef<HTMLDivElement>(null);
  const listScrollRef = useRef(0);

  useEffect(() => {
    if (!isOpen) {
      setActiveDetail(null);
      setSelectedItem(null);
      listScrollRef.current = 0;
    }
  }, [isOpen]);

  // activeDetail 이 null 로 돌아올 때(상세 → 목록) 저장된 scrollTop 복원.
  // React 렌더가 commit 된 직후 호출되어 목록 children 이 이미 DOM 에 있음.
  useEffect(() => {
    if (!activeDetail && bodyRef.current) {
      bodyRef.current.scrollTop = listScrollRef.current;
    }
  }, [activeDetail]);

  if (!isOpen) return null;

  async function handleSelectItem(item: NotificationItemType) {
    // 상세 진입 직전 목록 스크롤 위치 저장.
    if (bodyRef.current) {
      listScrollRef.current = bodyRef.current.scrollTop;
    }
    setSelectedItem(item);
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
          // 알림함은 최대 50개 fetch + 내부 스크롤. 높이는 600px 로 고정하여
          // 알림 개수와 무관하게 일관된 패널 크기를 유지한다. 작은 화면(< 628px viewport)에서는
          // viewport 에 맞춰 축소.
          height: 'min(600px, calc(100vh - 28px))',
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
          bottom: 80,
          left: 12,
          right: 12,
          // 모바일도 일정 높이 고정 + 내부 스크롤. 70dvh 와 600px 중 작은 값.
          // ActionBar(80px) + 안전 여백(56px) 을 제외한 viewport 범위 안.
          height: 'min(70dvh, 600px, calc(100dvh - 80px - 56px))',
          background: colors.panel,
          borderRadius: 20,
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
        {activeDetail ? (
          <button
            type="button"
            onClick={() => { setActiveDetail(null); setSelectedItem(null); }}
            style={{
              background: 'transparent',
              border: 'none',
              cursor: 'pointer',
              fontSize: 14,
              color: colors.inkSoft,
              fontFamily: fonts.sans,
              padding: 0,
            }}
          >
            ← 목록
          </button>
        ) : (
          <>
            <strong style={{ fontSize: 16, color: colors.ink }}>알림</strong>
            <button
              type="button"
              onClick={onClose}
              style={{
                background: 'transparent',
                border: 'none',
                cursor: 'pointer',
                fontSize: 14,
                color: colors.inkSoft,
                fontFamily: fonts.sans,
              }}
            >
              닫기
            </button>
          </>
        )}
      </div>

      {/* Body */}
      <div ref={bodyRef} style={{ overflowY: 'auto', flex: 1 }}>
        {loading ? (
          <div style={{ padding: 16, color: colors.inkSoft, fontSize: 13 }}>
            불러오는 중...
          </div>
        ) : activeDetail ? (
          <NotificationPinList
            pins={activeDetail.pins}
            onSelectPin={handleSelectPin}
            actorLabel={selectedItem?.registeredBy === currentUserId ? '내가' : `${activeDetail.registeredByNickname}님이`}
            type={activeDetail.type}
            createdAt={activeDetail.createdAt}
          />
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
          groupByDate(items).map(({ label, items: group }) => (
            <div key={label}>
              <div style={{
                padding: '8px 16px 4px',
                fontSize: 11,
                color: colors.inkSoft,
                fontWeight: 600,
                letterSpacing: '0.04em',
                textTransform: 'uppercase',
                fontFamily: fonts.sans,
              }}>
                {label}
              </div>
              {group.map((item) => (
                <NotificationItem
                  key={item.id}
                  item={item}
                  onClick={() => handleSelectItem(item)}
                  currentUserId={currentUserId}
                />
              ))}
            </div>
          ))
        )}
      </div>
    </div>
  );
}
