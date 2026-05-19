// newapp.jsx — DesignCanvas assembly

const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "showFrames": true
}/*EDITMODE-END*/;

// iOS frame wrapper for mobile screens
function MobileFrame({ children, showFrames }) {
  if (!showFrames) {
    return (
      <div style={{
        width: 390, height: 844, overflow: 'hidden', borderRadius: 28,
        boxShadow: '0 8px 40px rgba(26,26,46,0.18)', border: '1px solid rgba(26,26,46,0.1)',
        position: 'relative',
      }}>{children}</div>
    );
  }
  return (
    <IOSDevice width={390} height={844} dark={false}>
      <div style={{ width: '100%', height: '100%', overflow: 'hidden' }}>
        {children}
      </div>
    </IOSDevice>
  );
}

// Desktop frame wrapper
function DesktopFrame({ children, w = 1200, h = 768 }) {
  return (
    <div style={{
      width: w, height: h, overflow: 'hidden',
      borderRadius: 12, boxShadow: '0 8px 40px rgba(26,26,46,0.15)',
      border: '1px solid rgba(26,26,46,0.08)', background: '#FAF8F5',
    }}>{children}</div>
  );
}

function App() {
  const [t, setTweak] = useTweaks(TWEAK_DEFAULTS);

  const DW = 1200, DH = 768;
  const MW = 390,  MH = 844;
  const MF = (c) => <MobileFrame showFrames={t.showFrames}>{c}</MobileFrame>;
  const DF = (c) => <DesktopFrame w={DW} h={DH}>{c}</DesktopFrame>;

  return (
    <>
      <DesignCanvas>

        {/* ── 기본 시스템 화면 ───────────────────────────────── */}
        <DCSection id="basic" title="기본 시스템 — 스플래시 · 권한 · 빈 지도">
          <DCArtboard id="b-splash-m" label="Splash · 모바일" width={MW} height={MH}>
            {MF(<SplashScreen/>)}
          </DCArtboard>
          <DCArtboard id="b-splash-d" label="Splash · 데스크탑" width={DW} height={DH}>
            {DF(<SplashScreen/>)}
          </DCArtboard>
          <DCArtboard id="b-loc-m" label="위치 권한 · 모바일" width={MW} height={MH}>
            {MF(<LocationPermMobile/>)}
          </DCArtboard>
          <DCArtboard id="b-loc-d" label="위치 권한 · 데스크탑" width={DW} height={DH}>
            {DF(<LocationPermDesktop/>)}
          </DCArtboard>
          <DCArtboard id="b-notif" label="알림 권한 · 모바일" width={MW} height={MH}>
            {MF(<NotifPermMobile/>)}
          </DCArtboard>
          <DCArtboard id="b-empty-m" label="빈 지도 · 모바일" width={MW} height={MH}>
            {MF(<EmptyMapMobile/>)}
          </DCArtboard>
          <DCArtboard id="b-empty-d" label="빈 지도 · 데스크탑" width={DW} height={DH}>
            {DF(<EmptyMapDesktop/>)}
          </DCArtboard>
        </DCSection>

        {/* ── 로그인 & 온보딩 ──────────────────────────────── */}
        <DCSection id="common" title="로그인 & 온보딩">
          <DCArtboard id="s0" label="Screen 0 · 로그인" width={DW} height={DH}>
            {DF(<Screen0Login/>)}
          </DCArtboard>
          <DCArtboard id="s0a" label="Screen 0a · 로딩" width={MW} height={MH}>
            {MF(<Screen0aLoading/>)}
          </DCArtboard>
          <DCArtboard id="s0b" label="Screen 0b · 닉네임" width={MW} height={MH}>
            {MF(<Screen0bNickname/>)}
          </DCArtboard>
          <DCArtboard id="s0c" label="Screen 0c · 그룹 시작" width={MW} height={MH}>
            {MF(<Screen0cGroupStart/>)}
          </DCArtboard>
          <DCArtboard id="s1" label="Screen 1 · 그룹 선택" width={DW} height={DH}>
            {DF(<Screen1Groups/>)}
          </DCArtboard>
        </DCSection>

        {/* ── 데스크탑 D-1 ~ D-6 ────────────────────────── */}
        <DCSection id="desktop" title="데스크탑 (1200×768)">
          <DCArtboard id="d1" label="D-1 · 지도 메인" width={DW} height={DH}>
            {DF(<D1MapMain/>)}
          </DCArtboard>
          <DCArtboard id="d2" label="D-2 · 검색 패널" width={DW} height={DH}>
            {DF(<D2Search/>)}
          </DCArtboard>
          <DCArtboard id="d3" label="D-3 · 핀 추가 모드" width={DW} height={DH}>
            {DF(<D3AddPin/>)}
          </DCArtboard>
          <DCArtboard id="d4" label="D-4 · 메모/태그 패널" width={DW} height={DH}>
            {DF(<D4Memo/>)}
          </DCArtboard>
          <DCArtboard id="d5" label="D-5 · 핀 상세 (말풍선, 가게명 O)" width={DW} height={DH}>
            {DF(<D5PinDetail/>)}
          </DCArtboard>
          <DCArtboard id="d5b" label="D-5b · 핀 상세 (말풍선, 가게명 X)" width={DW} height={DH}>
            {DF(<D5bPinDetailNoName/>)}
          </DCArtboard>
          <DCArtboard id="d6" label="D-6 · 랜덤 추첨중" width={DW} height={DH}>
            {DF(<D6RandomSpin/>)}
          </DCArtboard>
          <DCArtboard id="d6b" label="D-6b · 랜덤 결과" width={DW} height={DH}>
            {DF(<D6RandomResult/>)}
          </DCArtboard>
        </DCSection>

        {/* ── 모바일 M-1 ~ M-7 ──────────────────────────── */}
        <DCSection id="mobile" title="모바일 (390×844)">
          <DCArtboard id="m1" label="M-1 · 지도 메인" width={MW} height={MH}>
            {MF(<M1MapMain/>)}
          </DCArtboard>
          <DCArtboard id="m2" label="M-2 · 검색 바텀시트" width={MW} height={MH}>
            {MF(<M2Search/>)}
          </DCArtboard>
          <DCArtboard id="m3" label="M-3 · 핀 추가 모드" width={MW} height={MH}>
            {MF(<M3AddPin/>)}
          </DCArtboard>
          <DCArtboard id="m4" label="M-4 · 메모/태그" width={MW} height={MH}>
            {MF(<M4Memo/>)}
          </DCArtboard>
          <DCArtboard id="m5" label="M-5 · 핀 상세 (가게명 O)" width={MW} height={MH}>
            {MF(<M5PinDetail/>)}
          </DCArtboard>
          <DCArtboard id="m5b" label="M-5b · 핀 상세 (가게명 X)" width={MW} height={MH}>
            {MF(<M5bPinDetailNoName/>)}
          </DCArtboard>
          <DCArtboard id="m6" label="M-6 · 랜덤 추첨중" width={MW} height={MH}>
            {MF(<M6RandomSpin/>)}
          </DCArtboard>
          <DCArtboard id="m6b" label="M-6b · 랜덤 결과" width={MW} height={MH}>
            {MF(<M6RandomResult/>)}
          </DCArtboard>
          <DCArtboard id="m7a" label="M-7 · 클러스터 (뭉친 상태)" width={MW} height={MH}>
            {MF(<M7Cluster/>)}
          </DCArtboard>
          <DCArtboard id="m7b" label="M-7 · 줌인 후 (펼쳐짐)" width={MW} height={MH}>
            {MF(<M7ClusterZoomed/>)}
          </DCArtboard>
        </DCSection>

      </DesignCanvas>

      <TweaksPanel>
        <TweakSection label="디바이스 프레임"/>
        <TweakToggle label="iOS 프레임 (모바일)" value={t.showFrames}
          onChange={(v) => setTweak('showFrames', v)}/>
      </TweaksPanel>
    </>
  );
}

const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(<App/>);
