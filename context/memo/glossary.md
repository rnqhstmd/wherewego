# memo 용어 사전

| 용어 | 설명 |
|------|------|
| 2초 룰 | 챗봇에 링크가 들어온 후 2초 이내 들어온 텍스트만 해당 장소의 메모로 인정 |
| 자동 메모 (AUTO) | 챗봇 2초 룰 매칭으로 저장된 메모. `pins.memo_source=AUTO` |
| 수동 메모 (MANUAL) | 웹 UI에서 직접 입력/수정한 메모. `pins.memo_source=MANUAL` |
| memo_source | 메모 출처 enum 컬럼. AUTO 또는 MANUAL |
| 수동 우선 정책 | MANUAL이 AUTO를 덮어쓸 수 있고, MANUAL 존재 시 후속 AUTO 매칭은 차단 |
| 메모 유실 | 2초 초과 후 들어온 텍스트는 메모로 인식하지 않음 |
| last_link 캐시 | botUserKey별 최근 링크 수신 시각/핀 ID를 보관하는 단기 캐시 (in-memory 또는 Redis) |
