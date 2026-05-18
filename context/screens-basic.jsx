// screens-basic.jsx — Splash, Permissions, Empty-state screens (mobile + desktop)

/* ── Splash — 브랜드만 노출 (앱 진입 직전) ───────────────── */
function SplashScreen() {
  return (
    <div style={{
      width: '100%', height: '100%', background: T.bg,
      fontFamily: F.sans, position: 'relative', overflow: 'hidden',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
    }}>
      <div style={{ position: 'absolute', inset: 0, display: 'flex',
        alignItems: 'center', justifyContent: 'center' }}>
        <GlobeBg w={520} h={520} style={{ opacity: 0.4 }}/>
      </div>
      <div style={{ position: 'relative', zIndex: 2, display: 'flex',
        flexDirection: 'column', alignItems: 'center', gap: 18 }}>
        <div style={{
          fontFamily: F.emo, fontSize: 48, fontWeight: 700,
          color: T.ink, letterSpacing: -1.5, lineHeight: 1.1, textAlign: 'center',
        }}>우리가 갈 지도</div>
        {/* Loading dots */}
        <div style={{ display: 'flex', gap: 6, marginTop: 12 }}>
          {[0, 1, 2].map(i => (
            <div key={i} style={{
              width: 6, height: 6, borderRadius: '50%', background: T.cta,
              opacity: i === 1 ? 1 : .4,
            }}/>
          ))}
        </div>
      </div>
    </div>
  );
}

/* ── 시스템 권한 모달 (재사용) ─────────────────────────── */
function PermissionDialog({ icon, title, body, primary = '허용', secondary = '나중에',
                            stack = 'horizontal', onMap = false }) {
  return (
    <div style={{
      position: 'absolute', inset: 0,
      background: onMap ? 'rgba(26,26,46,0.45)' : 'transparent',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      padding: 20, zIndex: 30,
    }}>
      <div style={{
        background: T.panel, borderRadius: 18,
        padding: '28px 24px 20px', width: '100%', maxWidth: 320,
        boxShadow: `0 10px 32px ${T.shadowMd}`, textAlign: 'center',
        fontFamily: F.sans,
      }}>
        {/* Icon */}
        <div style={{
          width: 60, height: 60, borderRadius: '50%',
          background: `${T.cta}15`,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          margin: '0 auto 18px',
        }}>{icon}</div>

        <div style={{ fontFamily: F.emo, fontSize: 22, fontWeight: 700,
          color: T.ink, letterSpacing: -.5, marginBottom: 10 }}>
          {title}
        </div>
        <div style={{ fontSize: 13.5, color: T.inkSoft, lineHeight: 1.6,
          marginBottom: 24 }}>
          {body}
        </div>

        {stack === 'horizontal' ? (
          <div style={{ display: 'flex', gap: 8 }}>
            <BtnSub style={{ flex: 1, padding: '11px 0' }}>{secondary}</BtnSub>
            <BtnPrimary style={{ flex: 1.4, padding: '11px 0' }}>{primary}</BtnPrimary>
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            <BtnPrimary style={{ padding: '12px 0' }}>{primary}</BtnPrimary>
            <BtnSub style={{ padding: '11px 0' }}>{secondary}</BtnSub>
          </div>
        )}
      </div>
    </div>
  );
}

/* ── 위치 권한 (mobile) ──────────────────────────────────── */
function LocationPermMobile() {
  return (
    <div style={{ width: '100%', height: '100%', position: 'relative',
      background: T.bg, overflow: 'hidden' }}>
      {/* Dim map behind for context */}
      <MapBg w="100%" h="100%"/>
      <PermissionDialog
        onMap
        icon={<IconLocation size={30} color={T.cta}/>}
        title="위치를 알려주세요"
        body={`근처에 어떤 핀이 있는지\n랜덤 뽑기에서 활용할 거예요`}
        primary="위치 사용 허용"
        secondary="나중에"
        stack="vertical"
      />
    </div>
  );
}

/* ── 위치 권한 (desktop) ─────────────────────────────────── */
function LocationPermDesktop() {
  return (
    <DesktopLayout>
      <div style={{ width: '100%', height: '100%', position: 'relative' }}>
        <MapBg w="100%" h="100%"/>
        <PermissionDialog
          onMap
          icon={<IconLocation size={30} color={T.cta}/>}
          title="위치를 알려주세요"
          body={`근처에 어떤 핀이 있는지\n랜덤 뽑기에서 활용할 거예요`}
          primary="위치 사용 허용"
          secondary="나중에"
          stack="vertical"
        />
      </div>
    </DesktopLayout>
  );
}

/* ── 알림 권한 (mobile) ──────────────────────────────────── */
function NotifPermMobile() {
  return (
    <div style={{ width: '100%', height: '100%',
      background: T.bg, position: 'relative', overflow: 'hidden',
      display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <PermissionDialog
        icon={<IconBell size={28} color={T.cta}/>}
        title="알림 받아볼래요?"
        body={`함께하는 사람이 핀을 추가하면\n알려드려요`}
        primary="알림 허용"
        secondary="다음에"
        stack="vertical"
      />
    </div>
  );
}

/* ── 빈 지도 (첫 사용자) — mobile ─────────────────────────── */
function EmptyMapMobile() {
  return (
    <MobileLayout>
      <div style={{ width: '100%', height: '100%', position: 'relative' }}>
        <MapBg w="100%" h="100%"/>
        {/* Center hint card */}
        <div style={{
          position: 'absolute', top: '46%', left: '50%',
          transform: 'translate(-50%, -50%)',
          background: T.panel, borderRadius: 18,
          padding: '22px 24px', width: 'calc(100% - 48px)', maxWidth: 300,
          boxShadow: `0 8px 24px ${T.shadowMd}, 0 0 0 1px ${T.hairline}`,
          textAlign: 'center', fontFamily: F.sans,
        }}>
          <div style={{ fontSize: 36, marginBottom: 6 }}>📍</div>
          <div style={{ fontFamily: F.emo, fontSize: 19, fontWeight: 700,
            color: T.ink, letterSpacing: -.4, marginBottom: 8 }}>
            첫 핀을 찍어볼까요?
          </div>
          <div style={{ fontSize: 13, color: T.inkSoft, lineHeight: 1.5,
            marginBottom: 16 }}>
            지도를 길게 누르거나<br/>아래 ＋ 버튼을 눌러보세요
          </div>
        </div>
        {/* Arrow pointing to + button */}
        <div style={{
          position: 'absolute', bottom: 12, left: '50%',
          transform: 'translateX(-50%)',
          display: 'flex', flexDirection: 'column', alignItems: 'center',
          color: T.cta, fontFamily: F.sans, fontSize: 12, fontWeight: 600,
        }}>
          <span style={{ marginBottom: 2 }}>여기를 눌러주세요</span>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
            stroke={T.cta} strokeWidth="2" strokeLinecap="round">
            <polyline points="6 9 12 15 18 9"/>
          </svg>
        </div>
      </div>
    </MobileLayout>
  );
}

/* ── 빈 지도 (첫 사용자) — desktop ─────────────────────────── */
function EmptyMapDesktop() {
  return (
    <DesktopLayout>
      <div style={{ width: '100%', height: '100%', position: 'relative' }}>
        <MapBg w="100%" h="100%"/>
        <div style={{
          position: 'absolute', top: '50%', left: '50%',
          transform: 'translate(-50%, -50%)',
          background: T.panel, borderRadius: 18,
          padding: '28px 32px', width: 360,
          boxShadow: `0 10px 28px ${T.shadowMd}, 0 0 0 1px ${T.hairline}`,
          textAlign: 'center', fontFamily: F.sans,
        }}>
          <div style={{ fontSize: 42, marginBottom: 8 }}>📍</div>
          <div style={{ fontFamily: F.emo, fontSize: 24, fontWeight: 700,
            color: T.ink, letterSpacing: -.5, marginBottom: 10 }}>
            아직 핀이 없어요
          </div>
          <div style={{ fontSize: 14, color: T.inkSoft, lineHeight: 1.6,
            marginBottom: 20 }}>
            지도를 클릭하거나 왼쪽 ＋ 버튼을<br/>눌러 첫 장소를 기록해보세요
          </div>
          <BtnPrimary style={{ padding: '12px 28px' }}>＋ 첫 핀 추가하기</BtnPrimary>
        </div>
      </div>
    </DesktopLayout>
  );
}

Object.assign(window, {
  SplashScreen,
  LocationPermMobile, LocationPermDesktop,
  NotifPermMobile,
  EmptyMapMobile, EmptyMapDesktop,
});
