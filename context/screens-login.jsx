// screens-login.jsx — Screen 0 (Login) + onboarding sequence + Screen 1 (Group Selection)

/* ── Screen 0 — 로그인 ───────────────────────────────────── */
function Screen0Login() {
  return (
    <div style={{
      width: '100%', height: '100%', position: 'relative',
      background: T.bg, fontFamily: F.sans, overflow: 'hidden',
      display: 'flex', alignItems: 'center', justifyContent: 'center',
    }}>
      {/* Globe background */}
      <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <GlobeBg w={680} h={680} style={{ opacity: 0.55 }}/>
      </div>

      {/* Center card */}
      <div style={{
        position: 'relative', zIndex: 2,
        display: 'flex', flexDirection: 'column', alignItems: 'center',
        maxWidth: 460, width: '90%',
      }}>
        {/* Brand wordmark */}
        <div style={{
          fontFamily: F.emo, fontSize: 56, fontWeight: 700,
          color: T.ink, letterSpacing: -1.5, lineHeight: 1.1, textAlign: 'center',
        }}>우리가 갈 지도</div>

        {/* Tagline */}
        <div style={{
          marginTop: 28, fontFamily: F.sans, fontSize: 15.5, color: T.inkSoft,
          textAlign: 'center', lineHeight: 1.65,
        }}>우리의 장소를 지도 위에 아카이빙해요</div>

        {/* Divider dots */}
        <div style={{ display: 'flex', gap: 8, marginTop: 32, alignItems: 'center' }}>
          <PinDot type="place"  size={8}/>
          <PinDot type="memory" size={11}/>
          <PinDot type="place"  size={8}/>
        </div>

        {/* Kakao button */}
        <BtnKakao style={{ marginTop: 32, maxWidth: 320 }}>
          <svg width="20" height="20" viewBox="0 0 20 20" style={{ flexShrink: 0 }}>
            <path d="M10 2C5.58 2 2 4.88 2 8.4c0 2.26 1.5 4.24 3.76 5.37l-.96 3.55 4.12-2.72c.36.05.72.07 1.08.07 4.42 0 8-2.88 8-6.4S14.42 2 10 2z"
              fill={T.kakaoInk}/>
          </svg>
          카카오로 시작하기
        </BtnKakao>

        <div style={{ marginTop: 18, fontSize: 12, color: T.inkFaint, textAlign: 'center', lineHeight: 1.5 }}>
          시작하면 서비스 이용약관 및 개인정보처리방침에 동의합니다
        </div>
      </div>

      {/* Corner dots */}
      <div style={{ position: 'absolute', bottom: 28, left: 28, display: 'flex', gap: 5 }}>
        {[T.pinPlace, T.pinMemory, T.inkFaint].map((c, i) => (
          <div key={i} style={{ width: 6, height: 6, borderRadius: '50%', background: c, opacity: .5 }}/>
        ))}
      </div>
    </div>
  );
}

/* ── Screen 0a — 로그인 중 (transitioning) ──────────────── */
function Screen0aLoading() {
  return (
    <div style={{
      width: '100%', height: '100%',
      background: T.bg, fontFamily: F.sans,
      display: 'flex', flexDirection: 'column',
      alignItems: 'center', justifyContent: 'center', gap: 28,
      position: 'relative', overflow: 'hidden',
    }}>
      {/* Background globe */}
      <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <GlobeBg w={520} h={520} style={{ opacity: 0.35 }}/>
      </div>

      <div style={{
        position: 'relative', zIndex: 2,
        display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 22,
      }}>
        {/* Spinner */}
        <div style={{
          width: 48, height: 48, borderRadius: '50%',
          border: `3px solid ${T.hairline}`,
          borderTopColor: T.cta,
          animation: 'spin 1s linear infinite',
        }}/>
        <div style={{
          fontFamily: F.emo, fontSize: 24, fontWeight: 700, color: T.ink, letterSpacing: -.5,
        }}>잠시만요</div>
        <div style={{ fontSize: 14, color: T.inkSoft, textAlign: 'center', lineHeight: 1.6 }}>
          카카오로 로그인하고 있어요
        </div>
      </div>
      <style>{`@keyframes spin { to { transform: rotate(360deg) } }`}</style>
    </div>
  );
}

/* ── Screen 0b — 닉네임 설정 ─────────────────────────────── */
function Screen0bNickname() {
  return (
    <div style={{
      width: '100%', height: '100%',
      background: T.bg, fontFamily: F.sans,
      display: 'flex', flexDirection: 'column',
      padding: '80px 32px 32px', boxSizing: 'border-box',
    }}>
      <div style={{ fontFamily: F.emo, fontSize: 32, fontWeight: 700, color: T.ink,
        lineHeight: 1.3, letterSpacing: -1 }}>
        반가워요<br/>이름을 알려주세요
      </div>
      <div style={{ marginTop: 12, fontSize: 14, color: T.inkSoft, lineHeight: 1.6 }}>
        함께하는 사람에게 보여질 이름이에요
      </div>

      {/* Input */}
      <div style={{ marginTop: 40, position: 'relative' }}>
        <input type="text" defaultValue="구본승" readOnly style={{
          width: '100%', padding: '14px 16px',
          border: 'none', borderBottom: `2px solid ${T.cta}`,
          background: 'transparent', fontFamily: F.emo,
          fontSize: 24, fontWeight: 700, color: T.ink,
          outline: 'none', borderRadius: 0,
        }}/>
        <div style={{ marginTop: 8, fontSize: 12, color: T.inkSoft }}>
          한글, 영문, 숫자 2~12자
        </div>
      </div>

      <div style={{ flex: 1 }}/>

      <BtnPrimary style={{ width: '100%', padding: '14px 0', fontSize: 15 }}>
        다음
      </BtnPrimary>
    </div>
  );
}

/* ── Screen 0c — 그룹 시작 (new group / join with code) ─── */
function Screen0cGroupStart() {
  return (
    <div style={{
      width: '100%', height: '100%',
      background: T.bg, fontFamily: F.sans,
      display: 'flex', flexDirection: 'column',
      padding: '70px 28px 32px', boxSizing: 'border-box',
    }}>
      <div style={{ fontFamily: F.emo, fontSize: 28, fontWeight: 700, color: T.ink,
        lineHeight: 1.3, letterSpacing: -1 }}>
        어떻게 시작할까요
      </div>
      <div style={{ marginTop: 10, fontSize: 14, color: T.inkSoft }}>
        혼자서도, 함께서도 괜찮아요
      </div>

      {/* Option 1: Create new group */}
      <div style={{
        marginTop: 32,
        background: T.panel, borderRadius: 16,
        border: `1.5px solid ${T.cta}`,
        padding: '20px 22px',
        cursor: 'pointer',
        boxShadow: `0 2px 10px ${T.shadow}`,
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
          <PinDot type="memory" size={14}/>
          <span style={{ fontFamily: F.emo, fontSize: 17, fontWeight: 700, color: T.ink }}>
            새 그룹 만들기
          </span>
        </div>
        <div style={{ fontSize: 13, color: T.inkSoft, lineHeight: 1.5 }}>
          이름을 정하고 친구를 초대해서<br/>함께 핀을 찍어요
        </div>
      </div>

      {/* Option 2: Join with code */}
      <div style={{
        marginTop: 12,
        background: 'transparent', borderRadius: 16,
        border: `1.5px solid ${T.hairline}`,
        padding: '20px 22px',
        cursor: 'pointer',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
          <span style={{ fontSize: 16 }}>🔗</span>
          <span style={{ fontFamily: F.emo, fontSize: 17, fontWeight: 700, color: T.ink }}>
            초대 코드로 합류
          </span>
        </div>
        <div style={{ fontSize: 13, color: T.inkSoft, lineHeight: 1.5 }}>
          받은 6자리 코드를 입력해서<br/>이미 만들어진 그룹에 들어가요
        </div>
      </div>

      <div style={{ flex: 1 }}/>

      <div style={{ textAlign: 'center', fontSize: 13, color: T.inkFaint }}>
        나중에 설정에서 변경할 수 있어요
      </div>
    </div>
  );
}

/* ── Screen 1 — 그룹 선택 (간소화 + 중앙정렬) ───────────── */
function Screen1Groups() {
  const groups = [
    { name: '봄이랑',     members: 2 },
    { name: '서울 데이트', members: 2 },
    { name: '본승의 노트', members: 1 },
  ];

  return (
    <div style={{
      width: '100%', height: '100%', background: T.bg,
      fontFamily: F.sans, overflow: 'hidden', display: 'flex', flexDirection: 'column',
    }}>
      {/* Top bar */}
      <div style={{
        height: 64, background: T.panel, borderBottom: `1px solid ${T.hairline}`,
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '0 32px', flexShrink: 0,
      }}>
        <span style={{ fontFamily: F.emo, fontSize: 22, fontWeight: 700, color: T.ink, letterSpacing: -.5 }}>
          우리가 갈 지도
        </span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <div style={{
            width: 32, height: 32, borderRadius: '50%',
            background: `linear-gradient(135deg, ${T.pinMemory}, ${T.pinPlace})`,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: 14,
          }}>🙂</div>
          <div style={{ fontSize: 13, fontWeight: 600, color: T.ink }}>구본승</div>
        </div>
      </div>

      {/* Center container */}
      <div style={{
        flex: 1, display: 'flex', flexDirection: 'column',
        alignItems: 'center', justifyContent: 'center',
        padding: 40, overflowY: 'auto',
      }}>
        {/* Heading */}
        <div style={{ textAlign: 'center', marginBottom: 36 }}>
          <div style={{ fontFamily: F.emo, fontSize: 30, fontWeight: 700,
            color: T.ink, letterSpacing: -1 }}>
            어떤 지도에 들어갈까요
          </div>
          <div style={{ marginTop: 10, fontSize: 14, color: T.inkSoft }}>
            참여 중인 그룹 {groups.length}개
          </div>
        </div>

        {/* Group cards */}
        <div style={{
          display: 'flex', flexDirection: 'column', gap: 12,
          width: '100%', maxWidth: 380,
        }}>
          {groups.map((g, i) => (
            <div key={i} style={{
              background: T.panel, borderRadius: 14,
              border: `1px solid ${T.hairline}`,
              padding: '18px 22px',
              boxShadow: `0 2px 8px ${T.shadow}`,
              cursor: 'pointer',
              display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            }}>
              <div>
                <div style={{ fontFamily: F.emo, fontSize: 18, fontWeight: 700,
                  color: T.ink, letterSpacing: -.3 }}>
                  {g.name}
                </div>
                <div style={{ marginTop: 4, fontSize: 12, color: T.inkSoft,
                  display: 'flex', alignItems: 'center', gap: 5 }}>
                  <span>👥</span>
                  <span>{g.members}명 참여 중</span>
                </div>
              </div>
              <span style={{ color: T.inkSoft, fontSize: 18 }}>→</span>
            </div>
          ))}

          {/* New group */}
          <div style={{
            background: 'transparent', borderRadius: 14,
            border: `1.5px dashed ${T.hairline}`,
            padding: '16px 22px',
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
            cursor: 'pointer', color: T.inkSoft,
          }}>
            <span style={{ fontSize: 18, color: T.ctaSub }}>＋</span>
            <span style={{ fontFamily: F.sans, fontSize: 14, fontWeight: 500 }}>
              새 그룹 만들기
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, {
  Screen0Login, Screen0aLoading, Screen0bNickname, Screen0cGroupStart,
  Screen1Groups,
});
