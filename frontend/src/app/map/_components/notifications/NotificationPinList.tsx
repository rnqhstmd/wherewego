'use client';

import { useRouter } from 'next/navigation';

import type { NotificationPinItem, NotificationType } from '@/lib/notifications/types';
import { colors, fonts } from '@/lib/design/tokens';

interface NotificationPinListProps {
  pins: NotificationPinItem[];
  onSelectPin: (pin: NotificationPinItem) => void;
  actorLabel: string;
  type: NotificationType;
  createdAt: string;
  /**
   * Phase 12 (FR-PIN-12-27): "📍 지도에서 보기" 버튼이 생성할 `?reel_bundle={id}` 쿼리에 사용.
   * 본 알림의 핀들(번들)을 지도에서 강조 표시하고, 나머지 핀은 opacity 0.3 으로 흐리게 처리한다.
   * `panelClose` 는 navigate 후 알림 패널을 닫기 위한 콜백.
   */
  notificationId: number;
  onBundleNavigate?: () => void;
}

/**
 * 알림 상세에서 핀 목록 표시. 상단 요약 카드 + 핀 리스트.
 *
 * <p>삭제된 핀은 disabled 처리. 릴스 출처 URL은 요약 카드 우측 링크로 표시.</p>
 */
export function NotificationPinList({
  pins,
  onSelectPin,
  actorLabel,
  type,
  createdAt,
  notificationId,
  onBundleNavigate,
}: NotificationPinListProps) {
  const router = useRouter();
  const sourceUrl = pins.find((p) => p.instagramUrl)?.instagramUrl ?? null;

  // Phase 12 (FR-PIN-12-27): CHATBOT_PINS 알림만 "지도에서 보기" 버튼 노출.
  //  - VISIT_DETECTED: 이미 사용자가 다녀온 핀이라 번들 강조가 의미 없음.
  //  - MANUAL_PIN: 짝꿍이 직접 추가한 단건이므로 기존 핀 선택 흐름(flyTo)으로 충분.
  const showMapButton =
    type === 'CHATBOT_PINS' && pins.some((p) => !p.deleted);

  const handleMapNavigate = () => {
    router.push(`/map?reel_bundle=${notificationId}`);
    onBundleNavigate?.();
  };

  if (pins.length === 0) {
    return (
      <div style={{ padding: 16, color: colors.inkSoft, fontFamily: fonts.sans, fontSize: 13 }}>
        핀이 없습니다.
      </div>
    );
  }

  return (
    <>
      {/* 요약 카드 */}
      <div style={{
        margin: '12px 16px',
        padding: '12px 14px',
        background: 'rgba(196,98,45,0.06)',
        borderRadius: 12,
        fontFamily: fonts.sans,
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'flex-start',
      }}>
        <div>
          <div style={{ fontSize: 13, color: colors.ink, fontWeight: 600, marginBottom: 3 }}>
            {type === 'VISIT_DETECTED'
              ? `함께 만든 추억 ${pins.length}곳`
              : `${actorLabel} 저장한 ${pins.length}곳`}
          </div>
          <div style={{ fontSize: 11, color: colors.inkSoft, fontFamily: fonts.mono }}>
            {formatTime(createdAt)}
          </div>
        </div>
        {sourceUrl && (
          <a
            href={sourceUrl}
            target="_blank"
            rel="noopener noreferrer"
            style={{
              fontSize: 11,
              color: '#C4622D',
              textDecoration: 'none',
              padding: '3px 8px',
              border: '1px solid rgba(196,98,45,0.3)',
              borderRadius: 99,
              whiteSpace: 'nowrap',
              marginLeft: 8,
              flexShrink: 0,
            }}
          >
            릴스 보기 ↗
          </a>
        )}
      </div>

      {/* Phase 12 (FR-PIN-12-27): CHATBOT_PINS 알림에만 지도 번들 진입 버튼.
          클릭 시 `/map?reel_bundle={notificationId}` 로 이동하여 본 알림에 묶인 핀을
          강조 표시 (비번들 opacity 0.3) + 상단 해제 배너 노출. */}
      {showMapButton && (
        <div style={{ padding: '0 16px 12px' }}>
          <button
            type="button"
            onClick={handleMapNavigate}
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
              padding: '8px 14px',
              borderRadius: 999,
              border: `1px solid ${colors.hairline}`,
              background: colors.panel,
              color: colors.ink,
              fontFamily: fonts.sans,
              fontSize: 12,
              fontWeight: 600,
              cursor: 'pointer',
            }}
          >
            <span aria-hidden="true">📍</span>
            <span>지도에서 보기</span>
          </button>
        </div>
      )}

      {/* 핀 목록 */}
      <ul style={{ listStyle: 'none', padding: 0, margin: 0 }}>
        {pins.map((pin) => {
          const disabled = pin.deleted;
          return (
            <li key={pin.pinId}>
              <button
                type="button"
                onClick={() => !disabled && onSelectPin(pin)}
                disabled={disabled}
                style={{
                  display: 'flex',
                  alignItems: 'flex-start',
                  gap: 10,
                  width: '100%',
                  padding: '12px 16px',
                  textAlign: 'left',
                  background: 'transparent',
                  border: 'none',
                  borderBottom: `1px solid ${colors.hairline}`,
                  cursor: disabled ? 'not-allowed' : 'pointer',
                  opacity: disabled ? 0.45 : 1,
                  fontFamily: fonts.sans,
                }}
              >
                <span style={{ fontSize: 16, marginTop: 1, flexShrink: 0 }}>📍</span>
                <div style={{ flex: 1 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span style={{
                      fontSize: 14,
                      color: disabled ? colors.inkSoft : colors.ink,
                      fontWeight: 500,
                      textDecoration: disabled ? 'line-through' : 'none',
                    }}>
                      {pin.placeName}
                    </span>
                    {disabled && (
                      <span style={{
                        fontSize: 10,
                        color: colors.inkSoft,
                        background: 'rgba(0,0,0,0.06)',
                        padding: '2px 6px',
                        borderRadius: 99,
                        fontWeight: 500,
                        flexShrink: 0,
                      }}>
                        삭제됨
                      </span>
                    )}
                    {/* Phase 10 FR-VD-29/31: VISIT_DETECTED 알림에서 현재 MEMORY 태그인 활성 핀에 추억 배지 표시. */}
                    {!disabled && type === 'VISIT_DETECTED' && pin.tag === 'MEMORY' && (
                      <span style={{
                        fontSize: 10,
                        color: '#FFB3C6',
                        background: 'rgba(255,179,198,0.15)',
                        padding: '2px 6px',
                        borderRadius: 99,
                        fontWeight: 600,
                        flexShrink: 0,
                      }}>
                        ● 추억
                      </span>
                    )}
                  </div>
                  {!disabled && pin.address && (
                    <div style={{
                      fontSize: 12,
                      color: colors.inkSoft,
                      marginTop: 2,
                      fontFamily: fonts.mono,
                    }}>
                      {pin.address}
                    </div>
                  )}
                  {!disabled && type === 'VISIT_DETECTED' && pin.memo && (
                    <div style={{
                      fontSize: 12,
                      color: colors.ink,
                      marginTop: 4,
                      padding: '6px 8px',
                      background: colors.bg,
                      borderRadius: 6,
                      whiteSpace: 'pre-wrap',
                    }}>
                      {pin.memo}
                    </div>
                  )}
                </div>
              </button>
            </li>
          );
        })}
      </ul>
    </>
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
