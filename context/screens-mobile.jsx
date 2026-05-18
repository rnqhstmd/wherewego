// screens-mobile.jsx — Mobile web (no app top bar; browser provides chrome)
// Sheets snap to bottom edge of action bar, sized to content
// Pin detail uses a speech-bubble popup pointing at the pin

const ACTIONBAR_H = 64;

/* ── Bottom action bar — icons only, no labels ──────────── */
function ActionBar({ active = null }) {
  const tabs = [
    { id: 'search', el: (c) => <IconSearch  size={22} color={c}/> },
    { id: 'add',    el: (c) => <IconPlus    size={22} color={c}/> },
    { id: 'dice',   el: (c) => <IconShuffle size={22} color={c}/> },
  ];
  return (
    <div style={{
      height: ACTIONBAR_H, background: T.panel,
      borderTop: `1px solid ${T.hairline}`,
      display: 'flex', alignItems: 'center',
      boxShadow: `0 -2px 12px ${T.shadow}`,
      flexShrink: 0, zIndex: 15,
    }}>
      {tabs.map((tab, i) => {
        const color = active === tab.id ? T.cta : T.inkSoft;
        return (
          <React.Fragment key={tab.id}>
            {i > 0 && <div style={{ width: 1, height: 24, background: T.hairline }}/>}
            <div style={{
              flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center',
              cursor: 'pointer', height: '100%',
            }}>
              {tab.el(color)}
            </div>
          </React.Fragment>
        );
      })}
    </div>
  );
}

/* ── Bottom sheet — content-sized, anchored to action bar ── */
function Sheet({ children, padTop = 6 }) {
  return (
    <div style={{
      position: 'absolute', bottom: 0, left: 0, right: 0,
      background: T.panel,
      borderTopLeftRadius: 20, borderTopRightRadius: 20,
      boxShadow: `0 -4px 24px ${T.shadowMd}`,
      zIndex: 20,
      paddingTop: padTop,
    }}>
      <DragHandle/>
      <div style={{ padding: '14px 20px 20px' }}>
        {children}
      </div>
    </div>
  );
}

/* ── Mobile wrapper ──────────────────────────────────────── */
function MobileLayout({ active = null, sheet = null, overlay = null, children }) {
  return (
    <div style={{
      width: '100%', height: '100%',
      display: 'flex', flexDirection: 'column',
      fontFamily: F.sans, overflow: 'hidden', background: T.bg,
    }}>
      {/* Map area takes the rest */}
      <div style={{ flex: 1, position: 'relative', overflow: 'hidden' }}>
        {children}
        {overlay}
        {sheet}
      </div>
      <ActionBar active={active}/>
    </div>
  );
}

function SearchResultRow({ name, addr }) {
  return (
    <div style={{ padding: '11px 0', borderBottom: `1px solid ${T.hairline}` }}>
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

/* ── M-1  지도 메인 ──────────────────────────────────────── */
function M1MapMain() {
  return (
    <MobileLayout active={null}>
      <MapBg w="100%" h="100%"/>
    </MobileLayout>
  );
}

/* ── M-2  검색 바텀시트 (콘텐츠 크기, 하단 정렬) ─────────── */
function M2Search() {
  return (
    <MobileLayout active="search" sheet={
      <Sheet>
        <Input placeholder="장소 검색..." style={{ marginBottom: 14 }}/>
        {[
          ['성수 카페거리', '서울 성동구 성수동'],
          ['성수역',         '서울 성동구 성수동1가'],
          ['성수동 와플집',   '서울 성동구 성수이로'],
        ].map(([n, a], i) => <SearchResultRow key={i} name={n} addr={a}/>)}
      </Sheet>
    }>
      <MapBg w="100%" h="100%" dimmed/>
    </MobileLayout>
  );
}

/* ── M-3  핀 추가 모드 ───────────────────────────────────── */
function M3AddPin() {
  return (
    <MobileLayout active="add" sheet={
      <Sheet padTop={2}>
        <div style={{ fontSize: 13, color: T.inkSoft, marginBottom: 10, fontFamily: F.mono }}>
          📍 서울 성동구 성수동
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <BtnSub style={{ flex: 1, padding: '11px 0' }}>취소</BtnSub>
          <BtnPrimary style={{ flex: 1, padding: '11px 0' }}>완료</BtnPrimary>
        </div>
      </Sheet>
    }>
      <MapBg w="100%" h="100%" crosshair/>
    </MobileLayout>
  );
}

/* ── M-4  메모/태그 바텀시트 ─────────────────────────────── */
function M4Memo() {
  return (
    <MobileLayout active="add" sheet={
      <Sheet>
        <div style={{ fontFamily: F.sans, fontSize: 16, fontWeight: 700, color: T.ink, marginBottom: 6 }}>
          새 핀 추가
        </div>
        <div style={{ fontFamily: F.mono, fontSize: 12, color: T.inkSoft, marginBottom: 16 }}>
          📍 서울 성동구 성수동2가 12-3
        </div>
        <HLine style={{ marginBottom: 14 }}/>

        <PanelLabel>태그</PanelLabel>
        <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
          <PinTag type="place" active/>
          <PinTag type="memory"/>
        </div>

        <PanelLabel>메모</PanelLabel>
        <div style={{
          border: `1.5px solid ${T.hairline}`, borderRadius: 10,
          padding: '12px 14px', height: 72, background: T.bg,
          fontSize: 14, color: T.inkFaint, marginBottom: 16,
        }}>메모 입력...</div>

        <div style={{ display: 'flex', gap: 8 }}>
          <BtnSub style={{ flex: 1, padding: '11px 0' }}>취소</BtnSub>
          <BtnPrimary style={{ flex: 1, padding: '11px 0' }}>저장</BtnPrimary>
        </div>
      </Sheet>
    }>
      <MapBg w="100%" h="100%" newPinXY={[490, 230]}/>
    </MobileLayout>
  );
}

/* ── M-5  핀 상세 — 말풍선 팝업 (가게명 있음) ──────────── */
function M5PinDetail() {
  return (
    <MobileLayout overlay={
      <SpeechBubblePopup
        pinX="52%" pinY="34%"
        memo={`여기 디저트 맛있대\n다음에 같이 가자!`}
        place="성수 카페거리"
        addr="Seongsu-dong, Seongdong-gu, Seoul"
        author="구본승"
        date="2026.03.12"
        pinType="place"
      />
    }>
      <MapBg w="100%" h="100%" highlightPin={[520, 200]}/>
    </MobileLayout>
  );
}

/* ── M-5b  핀 상세 — 가게명 없음 (메모 + 주소만) ─────────── */
function M5bPinDetailNoName() {
  return (
    <MobileLayout overlay={
      <SpeechBubblePopup
        pinX="50%" pinY="22%"
        memo="여기 뭔지 모르겠는데 분위기 좋아보임"
        place={null}
        addr="Seongsu-ro 100, Seongdong-gu, Seoul"
        author="이봄"
        date="2026.03.18"
        pinType="memory"
      />
    }>
      <MapBg w="100%" h="100%" highlightPin={[360, 140]}/>
    </MobileLayout>
  );
}

/* ── Speech-bubble popup is now defined in tokens.jsx ─── */

/* ── M-6  무작위 — 추첨중 (탭 하자마자 바로 이 화면) ────── */
function M6RandomSpin() {
  return (
    <MobileLayout active="dice" sheet={
      <Sheet>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '4px 0 8px' }}>
          {/* Animated cycling pins (static visual) */}
          <div style={{ display: 'flex', gap: 14, marginBottom: 14, alignItems: 'center' }}>
            <PinDot type="place"  size={14} style={{ opacity: .4 }}/>
            <PinDot type="memory" size={16}/>
            <div style={{
              width: 22, height: 22, borderRadius: '50%',
              border: `2.5px solid ${T.cta}`,
              borderTopColor: 'transparent',
              animation: 'spin 1s linear infinite',
            }}/>
            <PinDot type="place"  size={14}/>
            <PinDot type="memory" size={12} style={{ opacity: .4 }}/>
          </div>
          <div style={{ fontFamily: F.serif, fontSize: 18, fontWeight: 700, color: T.ink, marginBottom: 4 }}>
            추첨중...
          </div>
          <div style={{ fontFamily: F.mono, fontSize: 12, color: T.inkSoft }}>
            5km 이내 · 장소 핀 12개 중에서
          </div>
        </div>
        <style>{`@keyframes spin { to { transform: rotate(360deg) } }`}</style>
      </Sheet>
    }>
      <MapBg w="100%" h="100%" dimmed/>
    </MobileLayout>
  );
}

/* ── M-6c  무작위 — 결과 ─────────────────────────────────── */
function M6RandomResult() {
  return (
    <MobileLayout active="dice" sheet={
      <Sheet>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 14 }}>
          <IconShuffle size={16} color={T.cta}/>
          <span style={{ fontFamily: F.sans, fontSize: 13, fontWeight: 600, color: T.cta }}>
            오늘의 장소
          </span>
        </div>

        <div style={{
          background: T.bg, borderRadius: 12, border: `1px solid ${T.hairline}`,
          padding: '14px 16px', marginBottom: 14,
        }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
            <PinDot type="place" size={9}/>
            <span style={{ fontFamily: F.serif, fontSize: 16, fontWeight: 700, color: T.ink }}>
              을지로 파스타
            </span>
          </div>
          <div style={{ fontFamily: F.mono, fontSize: 11.5, color: T.inkSoft, marginBottom: 8 }}>
            Euljiro 3-ga, Jung-gu, Seoul
          </div>
          <div style={{ fontSize: 14, color: T.ink, lineHeight: 1.5 }}>
            "분위기 좋아 다음에 또 가자"
          </div>
        </div>

        <div style={{ display: 'flex', gap: 8 }}>
          <BtnPrimary style={{ flex: 1.6, padding: '12px 0' }}>지도에서 보기</BtnPrimary>
          <BtnSub style={{ flex: 1, padding: '12px 0',
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 6 }}>
            <IconShuffle size={14} color={T.ctaSub}/>
            <span>다시</span>
          </BtnSub>
        </div>
      </Sheet>
    }>
      <MapBg w="100%" h="100%" highlightPin={[150, 440]}/>
    </MobileLayout>
  );
}

/* ── M-7  클러스터 → 줌인 분리 ───────────────────────────── */
function M7Cluster() {
  return (
    <MobileLayout overlay={
      // Caption hint
      <div style={{
        position: 'absolute', top: 60, left: 16, right: 16,
        background: T.panel, padding: '10px 14px', borderRadius: 12,
        border: `1px solid ${T.hairline}`,
        boxShadow: `0 2px 8px ${T.shadow}`,
        fontFamily: F.sans, fontSize: 12, color: T.inkSoft,
        display: 'flex', alignItems: 'center', gap: 10, zIndex: 5,
      }}>
        <div style={{
          width: 24, height: 24, borderRadius: '50%', background: T.cta,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          color: 'white', fontWeight: 700, fontSize: 11, flexShrink: 0,
        }}>3</div>
        <span style={{ flex: 1, color: T.ink }}>
          가까운 핀이 묶여있어요. <span style={{ color: T.cta, fontWeight: 600 }}>탭하거나 확대하면</span> 펼쳐져요
        </span>
      </div>
    }>
      <MapBg w="100%" h="100%"/>
    </MobileLayout>
  );
}

/* ── M-7b  클러스터 줌인 후 ──────────────────────────────── */
function M7ClusterZoomed() {
  return (
    <MobileLayout overlay={
      <div style={{
        position: 'absolute', top: 60, left: 16, right: 16,
        background: T.panel, padding: '10px 14px', borderRadius: 12,
        border: `1px solid ${T.hairline}`,
        boxShadow: `0 2px 8px ${T.shadow}`,
        fontFamily: F.sans, fontSize: 12,
        display: 'flex', alignItems: 'center', gap: 10, zIndex: 5,
      }}>
        <span style={{ flex: 1, color: T.ink }}>
          ✓ 확대하니 핀이 <span style={{ color: T.pinPlace, fontWeight: 600 }}>●</span> <span style={{ color: T.pinMemory, fontWeight: 600 }}>♡</span> 각각 보여요
        </span>
      </div>
    }>
      <MapBg w="100%" h="100%" zoomedIn/>
    </MobileLayout>
  );
}

Object.assign(window, { M1MapMain, M2Search, M3AddPin, M4Memo,
  M5PinDetail, M5bPinDetailNoName,
  M6RandomSpin, M6RandomResult,
  M7Cluster, M7ClusterZoomed });
