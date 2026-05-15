# place 아키텍처

> 전체 구조 요약과 주제별 상세 문서 링크를 관리합니다.

## 시스템 구조

```
[URL] → ContentParser 디스패치 (MVP: InstagramParser만)
                          │
                          ▼
                HTML 메타 스크래핑 (og:title/og:description)
                          │
                          ▼
                  regex 장소명 추출
                  (📍 → 키워드 → 해시태그)
                          │
                ┌─────────┴─────────┐
                ▼                   ▼
        성공: 추출 텍스트       실패: 폴백 → 사용자 입력 ([[chatbot]])
                │
                ▼
        Kakao Local 검색 (동기)
                │
        ┌───────┴───────┐
        ▼               ▼
     결과 있음        결과 없음
        │               │
        │               ▼
        │       Google Places (비동기, language=ko)
        │               │
        └───────┬───────┘
                ▼
        결과 분기 (1건/복수/0건)
                │
                ▼
        [[pin]] 등록 위임 (tag=PLACE 기본값)
```

- 좌표 정규화: `{lat, lng}` 통일 (카카오 `y→lat`, `x→lng`)
- 멀티 플랫폼 확장 구조: `ContentParser` 인터페이스 + `InstagramParser` 구현체. TikTok/YouTube 추가 시 새 구현체만 등록
- 캐시: MVP 미적용. [[pin]] 중복 방지(`UNIQUE(group_id, instagram_url)`)로 동일 URL 재처리는 자연스럽게 차단됨

## 주제 문서

| 주제 | 설명 |
|------|------|
