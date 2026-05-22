'use client';

import type { NotificationPinItem } from '@/lib/notifications/types';
import { colors, fonts } from '@/lib/design/tokens';

interface NotificationPinListProps {
  pins: NotificationPinItem[];
  onSelectPin: (pin: NotificationPinItem) => void;
}

/**
 * 알림 상세에서 핀 목록 표시. 핀 클릭 → onSelectPin.
 *
 * <p>삭제된 핀은 disabled 처리하고 "삭제된 장소" prefix 로 표시한다.</p>
 */
export function NotificationPinList({ pins, onSelectPin }: NotificationPinListProps) {
  if (pins.length === 0) {
    return (
      <div style={{ padding: 16, color: colors.inkSoft, fontFamily: fonts.sans, fontSize: 13 }}>
        핀이 없습니다.
      </div>
    );
  }
  return (
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
                display: 'block',
                width: '100%',
                padding: '12px 16px',
                textAlign: 'left',
                background: 'transparent',
                border: 'none',
                borderBottom: `1px solid ${colors.hairline}`,
                cursor: disabled ? 'not-allowed' : 'pointer',
                opacity: disabled ? 0.5 : 1,
                fontFamily: fonts.sans,
              }}
            >
              <div style={{ fontSize: 14, color: colors.ink, fontWeight: 500 }}>
                {disabled ? `삭제된 장소: ${pin.placeName}` : pin.placeName}
              </div>
              {!disabled && pin.address && (
                <div
                  style={{
                    fontSize: 12,
                    color: colors.inkSoft,
                    marginTop: 2,
                    fontFamily: fonts.mono,
                  }}
                >
                  {pin.address}
                </div>
              )}
            </button>
          </li>
        );
      })}
    </ul>
  );
}
