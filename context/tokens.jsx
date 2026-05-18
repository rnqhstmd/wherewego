// tokens.jsx — Design tokens + shared primitives for MayGo redesign
// Spec colours, typography refs, reusable micro-components

const T = {
  bg:         '#FAF8F5',   // pastel beige canvas
  panel:      '#FFFFFF',   // sidebar / panels / sheets
  mapBg:      '#EAE4D4',   // map base
  mapWater:   '#D4E8F0',   // Han River / water
  mapPark:    '#D5E5CB',   // green areas
  mapBlock:   '#F0EBE0',   // city block fill
  mapRoad:    '#FFFFFF',   // road white
  pinPlace:   '#7BB3E8',   // ● 장소 blue
  pinMemory:  '#F4A8B0',   // ♡ 추억 pink
  pinNew:     '#E05A5A',   // 🔴 new pin
  cta:        '#C4622D',   // rust – 완료/저장
  ctaHover:   '#A84E23',
  ctaSub:     '#8B8B9E',   // grey – 취소/secondary
  kakao:      '#FEE500',
  kakaoInk:   '#191600',
  ink:        '#1A1A2E',   // deep navy
  inkSoft:    '#8B8B9E',
  inkFaint:   '#C5C5D0',
  hairline:   '#E8E4DE',
  shadow:     'rgba(26,26,46,0.08)',
  shadowMd:   'rgba(26,26,46,0.13)',
};

const F = {
  serif: '"Noto Serif KR", "Georgia", serif',
  emo:   '"Gowun Batang", "Nanum Myeongjo", serif',  // soft emotional serif
  sans:  '"Pretendard Variable", "Pretendard", -apple-system, "Apple SD Gothic Neo", system-ui, sans-serif',
  mono:  '"JetBrains Mono", "Courier New", monospace',
};

/* ── Shared micro-components ─────────────────────────────── */

// Primary rust button
function BtnPrimary({ children, style = {}, onClick }) {
  return (
    <button onClick={onClick} style={{
      background: T.cta, color: '#fff',
      border: 'none', borderRadius: 8,
      padding: '11px 20px', fontFamily: F.sans, fontSize: 14, fontWeight: 600,
      cursor: 'pointer', whiteSpace: 'nowrap',
      ...style,
    }}>{children}</button>
  );
}

// Ghost / secondary button
function BtnSub({ children, style = {}, onClick }) {
  return (
    <button onClick={onClick} style={{
      background: 'transparent', color: T.ctaSub,
      border: `1.5px solid ${T.hairline}`, borderRadius: 8,
      padding: '11px 20px', fontFamily: F.sans, fontSize: 14, fontWeight: 500,
      cursor: 'pointer', whiteSpace: 'nowrap',
      ...style,
    }}>{children}</button>
  );
}

// Kakao login button
function BtnKakao({ children, style = {} }) {
  return (
    <button style={{
      background: T.kakao, color: T.kakaoInk,
      border: 'none', borderRadius: 12,
      padding: '15px 28px', fontFamily: F.sans, fontSize: 16, fontWeight: 700,
      cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 10,
      width: '100%', justifyContent: 'center',
      ...style,
    }}>{children}</button>
  );
}

// Tag chip ● 장소 / ♡ 추억
function PinTag({ type = 'place', active = false, style = {}, onClick }) {
  const isPlace = type === 'place';
  const color   = isPlace ? T.pinPlace : T.pinMemory;
  const label   = isPlace ? '● 장소' : '♡ 추억';
  return (
    <button onClick={onClick} style={{
      background: active ? color : 'transparent',
      color: active ? '#fff' : T.ink,
      border: `1.5px solid ${active ? color : T.hairline}`,
      borderRadius: 999, padding: '7px 16px',
      fontFamily: F.sans, fontSize: 13, fontWeight: 600,
      cursor: 'pointer',
      ...style,
    }}>{label}</button>
  );
}

// Map pin marker — circle for place, heart for memory
function PinDot({ type = 'place', size = 10, ring = false, style = {} }) {
  if (type === 'memory') {
    // Pink heart marker
    const w = size * 1.5;
    return (
      <svg width={w} height={size * 1.3} viewBox="-8 -6 16 12"
        style={{ flexShrink: 0, filter: `drop-shadow(0 1px 3px ${T.pinMemory}80)`, ...style }}>
        <path d="M 0 4.5 C -7 0 -8 -5 -3.5 -5 C -1.5 -5 0 -3 0 -3 C 0 -3 1.5 -5 3.5 -5 C 8 -5 7 0 0 4.5 Z"
          fill={T.pinMemory}/>
      </svg>
    );
  }
  const color = type === 'new' ? T.pinNew : T.pinPlace;
  return (
    <div style={{
      width: size, height: size, borderRadius: '50%', background: color,
      boxShadow: ring ? `0 0 0 3px ${color}40, 0 2px 6px ${color}60` : `0 1px 4px ${color}80`,
      flexShrink: 0,
      ...style,
    }} />
  );
}

// Minimalist search icon — thin stroke
function IconSearch({ size = 18, color = 'currentColor', style = {} }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
      stroke={color} strokeWidth="1.5" strokeLinecap="round"
      style={style}>
      <circle cx="10.5" cy="10.5" r="6.5"/>
      <line x1="15.5" y1="15.5" x2="20" y2="20"/>
    </svg>
  );
}

// Plus icon — thin
function IconPlus({ size = 18, color = 'currentColor', style = {} }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
      stroke={color} strokeWidth="1.5" strokeLinecap="round" style={style}>
      <line x1="12" y1="5" x2="12" y2="19"/>
      <line x1="5" y1="12" x2="19" y2="12"/>
    </svg>
  );
}

// Location pin icon
function IconLocation({ size = 24, color = 'currentColor', style = {} }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
      stroke={color} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" style={style}>
      <path d="M12 22s-7-7.5-7-13a7 7 0 0 1 14 0c0 5.5-7 13-7 13z"/>
      <circle cx="12" cy="9" r="2.5"/>
    </svg>
  );
}

// Bell icon
function IconBell({ size = 24, color = 'currentColor', style = {} }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
      stroke={color} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" style={style}>
      <path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/>
      <path d="M13.7 21a2 2 0 0 1-3.4 0"/>
    </svg>
  );
}

// Close X
function IconClose({ size = 20, color = 'currentColor', style = {} }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
      stroke={color} strokeWidth="1.6" strokeLinecap="round" style={style}>
      <line x1="6" y1="6" x2="18" y2="18"/>
      <line x1="18" y1="6" x2="6" y2="18"/>
    </svg>
  );
}

/* ── Speech-bubble pin-detail popup (shared mobile + desktop) ─
   ┌────────────────────────────────────────┐
   │ 메모 본문                              │
   │ 메모 두번째 줄                          │
   │                                        │
   │ ● 가게이름 (optional)                  │
   │ Address line (mono)                    │
   │ ─────────────────────────              │
   │ yyyy.mm.dd  작성자             ⋮       │
   └────────────────────────────────────────┘
                  ▼ (tail to pin)                            ── */
function SpeechBubblePopup({ pinX, pinY, memo, place, addr, author, date, pinType, width = 296 }) {
  const lines = (memo || '').split('\n');
  return (
    <div style={{
      position: 'absolute',
      left: pinX, top: pinY,
      transform: 'translate(-50%, calc(-100% - 16px))',
      zIndex: 22,
    }}>
      <div style={{
        width, background: T.panel, borderRadius: 18,
        boxShadow: `0 10px 28px ${T.shadowMd}, 0 0 0 1px ${T.hairline}`,
        padding: '16px 18px 14px',
        fontFamily: F.sans, position: 'relative',
      }}>
        {/* Memo */}
        <div style={{ fontSize: 15, fontWeight: 500, color: T.ink, lineHeight: 1.5, letterSpacing: -.2 }}>
          {lines.map((l, i) => <div key={i}>{l}</div>)}
        </div>

        {/* Place + address */}
        <div style={{ marginTop: 12 }}>
          {place && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginBottom: 3 }}>
              <PinDot type={pinType} size={pinType === 'memory' ? 11 : 8}/>
              <span style={{ fontSize: 13.5, fontWeight: 700, color: T.ink, letterSpacing: -.2 }}>{place}</span>
            </div>
          )}
          <div style={{
            fontFamily: F.mono, fontSize: 11.5, color: T.inkSoft,
            letterSpacing: -.1, paddingLeft: place ? 18 : 0,
            display: 'flex', alignItems: 'center', gap: 6,
          }}>
            {!place && <PinDot type={pinType} size={pinType === 'memory' ? 11 : 8}/>}
            <span>{addr}</span>
          </div>
        </div>

        {/* Bottom row */}
        <div style={{
          marginTop: 12, paddingTop: 10, borderTop: `1px solid ${T.hairline}`,
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        }}>
          <div style={{ fontFamily: F.mono, fontSize: 12, color: T.inkSoft, fontStyle: 'italic' }}>
            {date}&nbsp;&nbsp;
            <span style={{ fontFamily: F.sans, fontStyle: 'normal', color: T.ink, fontWeight: 600 }}>{author}</span>
          </div>
          <button style={{
            width: 28, height: 28, borderRadius: 6,
            background: 'transparent', border: 'none', cursor: 'pointer',
            color: T.inkSoft, padding: 0,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
          }}>
            <IconMenuVert size={16} color={T.inkSoft}/>
          </button>
        </div>

        {/* Bubble tail */}
        <svg width="22" height="12" viewBox="0 0 22 12" style={{
          position: 'absolute', bottom: -11, left: '50%', transform: 'translateX(-50%)',
        }}>
          <path d="M 0 0 L 11 11 L 22 0 Z" fill={T.panel} stroke={T.hairline} strokeWidth="1"/>
          <path d="M 0.5 0 L 21.5 0" stroke={T.panel} strokeWidth="2"/>
        </svg>
      </div>
    </div>
  );
}

Object.assign(window, { IconSearch, IconPlus, IconLocation, IconBell, IconClose, SpeechBubblePopup });
function IconShuffle({ size = 20, color = 'currentColor', style = {} }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none"
      stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"
      style={style}>
      <polyline points="16 3 21 3 21 8"/>
      <line x1="4" y1="20" x2="21" y2="3"/>
      <polyline points="21 16 21 21 16 21"/>
      <line x1="15" y1="15" x2="21" y2="21"/>
      <line x1="4" y1="4" x2="9" y2="9"/>
    </svg>
  );
}

// Vertical 3 dots (⋮) icon button
function IconMenuVert({ size = 18, color = 'currentColor' }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill={color}>
      <circle cx="12" cy="5"  r="1.8"/>
      <circle cx="12" cy="12" r="1.8"/>
      <circle cx="12" cy="19" r="1.8"/>
    </svg>
  );
}

// Cluster bubble "○ N"
function Cluster({ n = 3, style = {} }) {
  return (
    <div style={{
      width: 32, height: 32, borderRadius: '50%',
      background: T.panel, border: `2px solid ${T.hairline}`,
      boxShadow: `0 2px 8px ${T.shadow}`,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      fontFamily: F.sans, fontSize: 12, fontWeight: 700, color: T.ink,
      ...style,
    }}>{n}</div>
  );
}

// Drag handle bar (mobile bottom sheets)
function DragHandle() {
  return (
    <div style={{ width: 36, height: 4, borderRadius: 2, background: T.inkFaint, margin: '12px auto 0' }} />
  );
}

// Hairline divider
function HLine({ style = {} }) {
  return <div style={{ height: 1, background: T.hairline, ...style }} />;
}

// Section label above panel rows
function PanelLabel({ children, style = {} }) {
  return (
    <div style={{ fontFamily: F.sans, fontSize: 11, fontWeight: 600, letterSpacing: '.08em',
      textTransform: 'uppercase', color: T.inkSoft, marginBottom: 8, ...style }}>
      {children}
    </div>
  );
}

// Input field
function Input({ placeholder, style = {} }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 8,
      border: `1.5px solid ${T.hairline}`, borderRadius: 10,
      padding: '10px 14px', background: T.bg,
      fontFamily: F.sans, fontSize: 14, color: T.inkSoft,
      ...style,
    }}>
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none"
        stroke={T.inkFaint} strokeWidth="2" strokeLinecap="round">
        <circle cx="11" cy="11" r="7"/><line x1="16.5" y1="16.5" x2="21" y2="21"/>
      </svg>
      <span>{placeholder}</span>
    </div>
  );
}

// Mini progress bar row  "● ████░░  N"
function PinBar({ type, count, total }) {
  const color = type === 'place' ? T.pinPlace : T.pinMemory;
  const sym   = type === 'place' ? '●' : '♡';
  const pct   = total ? Math.min(count / total, 1) : 0;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, fontFamily: F.sans }}>
      <span style={{ color, fontWeight: 700, width: 12 }}>{sym}</span>
      <div style={{ flex: 1, height: 5, borderRadius: 3, background: T.hairline, position: 'relative' }}>
        <div style={{ position: 'absolute', left: 0, top: 0, height: '100%', width: `${pct * 100}%`,
          borderRadius: 3, background: color }} />
      </div>
      <span style={{ color: T.ink, fontWeight: 600, width: 20, textAlign: 'right' }}>{count}</span>
    </div>
  );
}

Object.assign(window, {
  T, F,
  BtnPrimary, BtnSub, BtnKakao,
  PinTag, PinDot, Cluster, DragHandle, HLine, PanelLabel, Input, PinBar,
  IconShuffle, IconMenuVert,
});
