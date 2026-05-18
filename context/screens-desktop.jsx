// screens-desktop.jsx — D-1 through D-6 (1200×768 desktop layout)

/* ── Shared Desktop Chrome ───────────────────────────────── */
const SIDEBAR_W = 52;
const PANEL_W   = 280;

const NAV_ITEMS = [
  { id: 'search', icon: 'search' },
  { id: 'add',    icon: 'add'    },
  { id: 'dice',   icon: 'shuffle'},
];

function NavIcon({ name, color }) {
  if (name === 'shuffle') return <IconShuffle size={20} color={color}/>;
  if (name === 'search')  return <IconSearch  size={20} color={color}/>;
  return <IconPlus size={20} color={color}/>;
}

function Sidebar({ active = null }) {
  return (
    <div style={{
      width: SIDEBAR_W, height: '100%', flexShrink: 0,
      background: T.panel, borderRight: `1px solid ${T.hairline}`,
      display: 'flex', flexDirection: 'column', alignItems: 'center',
      paddingBottom: 16, boxSizing: 'border-box',
      boxShadow: `1px 0 0 ${T.hairline}`,
      zIndex: 10,
    }}>
      {/* Brand */}
      <div style={{
        width: '100%', padding: '14px 0 12px',
        display: 'flex', flexDirection: 'column', alignItems: 'center',
        borderBottom: `1px solid ${T.hairline}`,
      }}>
        <div style={{ fontFamily: F.serif, fontSize: 9.5, fontWeight: 800, color: T.ink,
          textAlign: 'center', lineHeight: 1.3 }}>
          우리가<br/>갈지도
        </div>
      </div>

      {/* Nav items */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4, paddingTop: 14 }}>
        {NAV_ITEMS.map((item, i) => {
          const isActive = active === item.id;
          return (
            <React.Fragment key={item.id}>
              {i > 0 && (
                <div style={{ width: 24, height: 1, background: T.hairline, margin: '4px 0' }}/>
              )}
              <div style={{
                width: 40, height: 40, borderRadius: 10,
                background: isActive ? `${T.cta}15` : 'transparent',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                cursor: 'pointer', position: 'relative',
              }}>
                {isActive && (
                  <div style={{
                    position: 'absolute', left: -6, top: '50%', transform: 'translateY(-50%)',
                    width: 3, height: 24, borderRadius: '0 2px 2px 0', background: T.cta,
                  }}/>
                )}
                <NavIcon name={item.icon} color={isActive ? T.cta : T.inkSoft}/>
              </div>
            </React.Fragment>
          );
        })}
      </div>
    </div>
  );
}

// Slide-in panel
function SidePanel({ title, onClose, children }) {
  return (
    <div style={{
      width: PANEL_W, height: '100%', flexShrink: 0,
      background: T.panel, borderRight: `1px solid ${T.hairline}`,
      display: 'flex', flexDirection: 'column',
      boxShadow: `3px 0 16px ${T.shadow}`,
      zIndex: 9, overflow: 'hidden',
    }}>
      {/* Header */}
      <div style={{
        padding: '20px 20px 16px',
        borderBottom: `1px solid ${T.hairline}`,
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        flexShrink: 0,
      }}>
        <span style={{ fontFamily: F.sans, fontSize: 15, fontWeight: 700, color: T.ink }}>{title}</span>
        {onClose && (
          <button style={{ background: 'none', border: 'none', cursor: 'pointer',
            fontSize: 16, color: T.inkSoft, padding: '2px 6px' }}>✕ 닫기</button>
        )}
      </div>
      <div style={{ flex: 1, overflowY: 'auto', padding: '16px 20px' }}>
        {children}
      </div>
    </div>
  );
}

// Desktop wrapper
function DesktopLayout({ active, panel, children }) {
  return (
    <div style={{ width: '100%', height: '100%', display: 'flex', fontFamily: F.sans, overflow: 'hidden' }}>
      <Sidebar active={active}/>
      {panel}
      <div style={{ flex: 1, position: 'relative', overflow: 'hidden' }}>
        {children}
      </div>
    </div>
  );
}

// Address bar for add-pin mode
function AddressBar({ addr }) {
  return (
    <div style={{
      position: 'absolute', bottom: 0, left: 0, right: 0,
      background: T.panel, borderTop: `1px solid ${T.hairline}`,
      padding: '14px 24px',
      display: 'flex', alignItems: 'center', gap: 12,
      boxShadow: `0 -2px 12px ${T.shadow}`, zIndex: 5,
    }}>
      <span style={{ fontSize: 16 }}>📍</span>
      <span style={{ flex: 1, fontFamily: F.sans, fontSize: 14, color: T.ink }}>{addr}</span>
      <BtnSub style={{ padding: '8px 18px' }}>취소</BtnSub>
      <BtnPrimary style={{ padding: '8px 18px' }}>완료</BtnPrimary>
    </div>
  );
}

/* ── Search result row ───────────────────────────────────── */
function SearchRow({ name, addr }) {
  return (
    <div style={{
      padding: '12px 0', borderBottom: `1px solid ${T.hairline}`,
      cursor: 'pointer',
    }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
        <span style={{ fontSize: 14, marginTop: 1 }}>📍</span>
        <div>
          <div style={{ fontSize: 14, fontWeight: 600, color: T.ink }}>{name}</div>
          <div style={{ fontSize: 12, color: T.inkSoft, marginTop: 2, fontFamily: F.mono }}>{addr}</div>
        </div>
      </div>
    </div>
  );
}

/* ── D-1  지도 메인 ──────────────────────────────────────── */
function D1MapMain() {
  return (
    <DesktopLayout>
      <div style={{ width: '100%', height: '100%' }}>
        <MapBg w="100%" h="100%"/>
      </div>
    </DesktopLayout>
  );
}

/* ── D-2  검색 패널 ──────────────────────────────────────── */
function D2Search() {
  return (
    <DesktopLayout active="search" panel={
      <SidePanel title="장소 검색" onClose>
        <Input placeholder="성수..." style={{ marginBottom: 16 }}/>
        <div style={{ fontSize: 12, color: T.inkSoft, marginBottom: 10, fontWeight: 600 }}>검색 결과</div>
        <HLine style={{ marginBottom: 0 }}/>
        {[
          ['성수 카페거리', '서울 성동구 성수동'],
          ['성수역', '서울 성동구 성수동1가'],
          ['성수동 와플집', '서울 성동구 성수이로'],
        ].map(([n, a], i) => <SearchRow key={i} name={n} addr={a}/>)}
      </SidePanel>
    }>
      <MapBg w="100%" h="100%"/>
    </DesktopLayout>
  );
}

/* ── D-3  핀 추가 모드 ───────────────────────────────────── */
function D3AddPin() {
  return (
    <DesktopLayout active="add">
      <div style={{ width: '100%', height: '100%', position: 'relative' }}>
        <MapBg w="100%" h="100%" crosshair/>
        <AddressBar addr="서울 성동구 성수동2가 12-3"/>
      </div>
    </DesktopLayout>
  );
}

/* ── D-4  메모/태그 패널 ─────────────────────────────────── */
function D4Memo() {
  return (
    <DesktopLayout active="add" panel={
      <SidePanel title="새 핀 추가">
        {/* Address */}
        <div style={{ padding: '10px 14px', background: T.bg, borderRadius: 10, marginBottom: 16 }}>
          <div style={{ fontSize: 11, color: T.inkSoft, marginBottom: 4 }}>📍 위치</div>
          <div style={{ fontSize: 14, fontWeight: 600, color: T.ink }}>서울 성동구 성수동2가 12-3</div>
        </div>

        {/* Tag */}
        <PanelLabel>태그</PanelLabel>
        <div style={{ display: 'flex', gap: 8, marginBottom: 20 }}>
          <PinTag type="place"  active/>
          <PinTag type="memory"/>
        </div>

        {/* Memo */}
        <PanelLabel>메모</PanelLabel>
        <div style={{
          border: `1.5px solid ${T.hairline}`, borderRadius: 10,
          padding: '12px 14px', minHeight: 90, background: T.bg,
          fontSize: 14, color: T.inkFaint, marginBottom: 20,
        }}>메모 입력...</div>

        {/* Actions */}
        <HLine style={{ marginBottom: 16 }}/>
        <div style={{ display: 'flex', gap: 8 }}>
          <BtnSub style={{ flex: 1 }}>취소</BtnSub>
          <BtnPrimary style={{ flex: 1 }}>저장</BtnPrimary>
        </div>
      </SidePanel>
    }>
      <div style={{ width: '100%', height: '100%', position: 'relative' }}>
        <MapBg w="100%" h="100%" newPinXY={[840, 330]}/>
      </div>
    </DesktopLayout>
  );
}

/* ── D-5  핀 상세 — 모바일과 동일한 말풍선 ───────────────── */
function D5PinDetail() {
  // pin [520, 200] in 1000×800 → 52% × 25%
  // but the map area on desktop excludes sidebar; pinX% is in map area
  return (
    <DesktopLayout>
      <div style={{ width: '100%', height: '100%', position: 'relative' }}>
        <MapBg w="100%" h="100%" highlightPin={[520, 200]}/>
        <SpeechBubblePopup
          pinX="52%" pinY="34%"
          memo={`여기 디저트 맛있대\n다음에 같이 가자!`}
          place="성수 카페거리"
          addr="Seongsu-dong, Seongdong-gu, Seoul"
          author="구본승"
          date="2026.03.12"
          pinType="place"
          width={320}
        />
      </div>
    </DesktopLayout>
  );
}

/* ── D-5b  핀 상세 (가게명 없음) ──────────────────────────── */
function D5bPinDetailNoName() {
  return (
    <DesktopLayout>
      <div style={{ width: '100%', height: '100%', position: 'relative' }}>
        <MapBg w="100%" h="100%" highlightPin={[360, 140]}/>
        <SpeechBubblePopup
          pinX="36%" pinY="22%"
          memo="여기 뭔지 모르겠는데 분위기 좋아보임"
          place={null}
          addr="Seongsu-ro 100, Seongdong-gu, Seoul"
          author="이봄"
          date="2026.03.18"
          pinType="memory"
          width={320}
        />
      </div>
    </DesktopLayout>
  );
}

/* ── D-6  무작위 — 탭 누르면 바로 추첨중 ─────────────────── */
function D6RandomSpin() {
  return (
    <DesktopLayout active="dice" panel={
      <SidePanel title={<><IconShuffle size={16} color={T.ink}/> &nbsp;무작위 선정</>}>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '36px 0 24px' }}>
          <div style={{ display: 'flex', gap: 14, marginBottom: 16, alignItems: 'center' }}>
            <PinDot type="place"  size={14} style={{ opacity: .4 }}/>
            <PinDot type="memory" size={16}/>
            <div style={{
              width: 26, height: 26, borderRadius: '50%',
              border: `2.5px solid ${T.cta}`, borderTopColor: 'transparent',
              animation: 'spin 1s linear infinite',
            }}/>
            <PinDot type="place"  size={14}/>
            <PinDot type="memory" size={12} style={{ opacity: .4 }}/>
          </div>
          <div style={{ fontFamily: F.serif, fontSize: 18, fontWeight: 700, color: T.ink, marginBottom: 6 }}>
            추첨중...
          </div>
          <div style={{ fontFamily: F.mono, fontSize: 12, color: T.inkSoft }}>
            5km 이내 · 핀 12개 중에서
          </div>
        </div>
        <style>{`@keyframes spin { to { transform: rotate(360deg) } }`}</style>
      </SidePanel>
    }>
      <MapBg w="100%" h="100%" dimmed/>
    </DesktopLayout>
  );
}

/* ── D-6b  무작위 — 결과 ─────────────────────────────────── */
function D6RandomResult() {
  return (
    <DesktopLayout active="dice" panel={
      <SidePanel title={<><IconShuffle size={16} color={T.ink}/> &nbsp;오늘의 장소</>}>
        <div style={{
          background: T.bg, borderRadius: 12, border: `1px solid ${T.hairline}`,
          padding: '14px 16px', marginBottom: 16,
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
            <PinDot type="place" size={10}/>
            <span style={{ fontFamily: F.serif, fontSize: 16, fontWeight: 700, color: T.ink }}>
              을지로 파스타
            </span>
          </div>
          <div style={{ fontFamily: F.mono, fontSize: 11.5, color: T.inkSoft, marginBottom: 8 }}>
            Euljiro 3-ga, Jung-gu, Seoul
          </div>
          <div style={{ fontSize: 13.5, color: T.ink, lineHeight: 1.5 }}>"분위기 좋아 다음에 또 가자"</div>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <BtnPrimary>지도에서 보기</BtnPrimary>
          <BtnSub style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6 }}>
            <IconShuffle size={14} color={T.ctaSub}/>
            <span>다시 선정</span>
          </BtnSub>
        </div>
      </SidePanel>
    }>
      <MapBg w="100%" h="100%" highlightPin={[150, 440]}/>
    </DesktopLayout>
  );
}

Object.assign(window, { D1MapMain, D2Search, D3AddPin, D4Memo,
  D5PinDetail, D5bPinDetailNoName,
  D6RandomSpin, D6RandomResult });
