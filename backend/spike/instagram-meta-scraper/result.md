# 인스타 릴스 메타 스크래핑 Spike 결과

설계서: `.dev/feature-phase-0-foundation/design.md` §3.4

## 법적 리스크

Meta Instagram ToS §II는 자동화된 데이터 수집(scraping)을 금지합니다. 본 spike의 `CHROME_UA`/`FULL_HEADERS` 전략은 브라우저 위장으로 차단을 우회하는 의도가 있어 ToS 위반 + CFAA 등 법적 대응 대상이 될 수 있습니다.

- **운영 코드 적용 금지**: spike는 기술 검증 목적으로만 사용. 본 코드는 그대로 운영 코드에 통합하지 마십시오.
- **대안 검토**: Apify Instagram Scraper, Instagram Basic Display API(공식, OAuth 필요), 사용자 직접 입력 폴백.
- **Phase 8 진입 전 법무 검토 필수**.

## 실행 요약

- 상태: _(미실행)_
- 총 URL 수: _(미실행)_
- 단계별 차단율: _(미실행)_
- og:description 유효율: _(미실행)_
- 장소명 추출 성공률: _(미실행)_
- 패턴별 기여도: _(미실행)_

## 단계별 차단율 (AC-18)

| Strategy | 시도 | 차단 | 차단율 | og:desc 유효 |
|----------|------|------|--------|--------------|
| NO_UA | _(미실행)_ | _(미실행)_ | _(미실행)_ | _(미실행)_ |
| CHROME_UA | _(미실행)_ | _(미실행)_ | _(미실행)_ | _(미실행)_ |
| FULL_HEADERS | _(미실행)_ | _(미실행)_ | _(미실행)_ | _(미실행)_ |

## 패턴별 기여도 (AC-19)

| 패턴 | 매칭 수 | 비율 |
|------|---------|------|
| EMOJI_PIN | _(미실행)_ | _(미실행)_ |
| KEYWORD | _(미실행)_ | _(미실행)_ |
| HASHTAG | _(미실행)_ | _(미실행)_ |

## 결론 및 다음 단계

- AC-17 (실행 가능): runSpike Gradle 태스크 등록 완료
- AC-18 (차단율 측정): 위 단계별 차단율 표 참조 (실행 후 갱신)
- AC-19 (패턴 기여도): 위 패턴별 기여도 표 참조 (실행 후 갱신)
- 차단율 >30% 시: ADR-0001 재도입 트리거 발동 → Kafka/Redis 기반 비동기 처리 검토
- Phase 8 PRD 갱신 권고: 본 결과 링크 추가 (구현 순서 Wave 7)

## 실행 방법

```bash
# 1) sample-urls.txt에 100개 URL 채우기
# 2) 루트에서 실행
./gradlew :spike:instagram-meta-scraper:runSpike
# 3) 본 result.md가 통계로 갱신됨
# 4) samples/ 디렉토리에 HTML 응답이 <shortcode>__<strategy>.html로 저장됨 (Git 추적 안 함)
```
