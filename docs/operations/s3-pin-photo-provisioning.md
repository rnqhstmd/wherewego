# S3 버킷 프로비저닝 — 추억핀 사진 업로드 (Phase 13)

> 추억(MEMORY) 핀 사진 1장을 저장하는 S3 버킷의 생성·정책·권한·환경변수·비용 알림 가이드. 운영자 1인(rnqhstmd) 기준.

DB 에는 S3 객체 키만 저장하고, 공개 URL 은 `wherewego.s3.public-base-url` 과 키를 조합해 만든다. 4인 규모에서 **AWS S3 프리티어(5GB / GET 2만 / PUT·LIST 2천 / 전송 100GB) 안에서 무과금 운영**을 전제로 한다.

---

## 1. 버킷 생성

| 항목 | 값 |
|------|-----|
| 리전 | `ap-northeast-2` (서울) — `S3_REGION` 기본값과 일치 |
| 버킷 이름 | 예: `wherewego-pin-photos` (전역 고유, 소문자) |
| 객체 소유권 | ACL 비활성화 (버킷 소유자 권장 기본값) |
| 버전 관리 | 비활성화 (교체는 best-effort 삭제 + 신규 put, 이력 불필요) |

키 스킴: `pins/{groupId}/{pinId}/{uuid}.jpg` (원본) / `pins/{groupId}/{pinId}/{uuid}_thumb.webp` (썸네일).

---

## 2. 퍼블릭 액세스 + 버킷 정책

`pins/*` 객체만 공개 GET 을 허용한다. UUID 키라 열거 불가하고 LIST 권한은 부여하지 않는다 (BR-8).

1. 버킷 → **권한 → 퍼블릭 액세스 차단** 에서 "새 버킷 정책을 통해 부여된 퍼블릭 액세스 차단" 항목을 **해제**한다. (다른 차단 항목은 유지 가능 — 정책 기반 공개만 열면 됨.)
2. **버킷 정책** 에 `pins/*` 공개 GetObject 추가:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "PublicGetPinPhotos",
      "Effect": "Allow",
      "Principal": "*",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::wherewego-pin-photos/pins/*"
    }
  ]
}
```

---

## 3. EC2 IAM Role 권한

애플리케이션은 `DefaultCredentialsProvider` 로 EC2 인스턴스 IAM Role 자격증명을 사용한다. 업로드/삭제만 필요하며 **LIST 는 불필요**(BR-8).

EC2 인스턴스 프로파일 Role 에 다음 인라인 정책 부착:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "PinPhotoPutDelete",
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::wherewego-pin-photos/pins/*"
    }
  ]
}
```

로컬 개발은 `.env` 에 AWS 자격증명(`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`) 또는 AWS 프로필을 설정하면 `DefaultCredentialsProvider` 가 동일하게 해석한다.

---

## 4. 환경변수 (`wherewego.s3.*`)

| 환경변수 | 매핑 | 예시 | 비고 |
|----------|------|------|------|
| `S3_BUCKET` | `wherewego.s3.bucket` | `wherewego-pin-photos` | 필수 |
| `S3_REGION` | `wherewego.s3.region` | `ap-northeast-2` | 기본값 `ap-northeast-2` |
| `S3_PUBLIC_BASE_URL` | `wherewego.s3.public-base-url` | `https://wherewego-pin-photos.s3.ap-northeast-2.amazonaws.com` | 필수. 끝에 `/` 없이. 응답 URL = `{base}/{key}` |

`S3_PUBLIC_BASE_URL` 은 버킷 가상 호스팅 URL 을 그대로 사용하거나, CloudFront/커스텀 도메인을 둔 경우 그 베이스를 넣는다.

---

## 5. AWS Budgets $0.01 알림

프리티어 초과 즉시 인지하기 위한 운영 안전망.

1. **Billing → Budgets → Create budget → Cost budget**.
2. 예산 금액 **$0.01**, 기간 월간.
3. 알림 임계값 **실제 비용(actual) > $0.01** 도달 시 이메일 알림(운영자 메일).

S3 사용량이 프리티어를 벗어나 과금이 시작되면 첫 센트에서 알림이 온다.

---

## 6. 배포 검증 (1회)

scrimage-webp 는 번들된 **libwebp 네이티브 바이너리**로 WebP 썸네일을 인코딩한다. EC2(Linux) 첫 배포 시 **실제 업로드 1건**으로 네이티브 로딩 성공을 1회 검증한다.

- 실패 시 `UnsatisfiedLinkError` 발생 → JVM/컨테이너 임시 디렉토리 쓰기 권한 확인.
- 업로드 성공 후 응답 `photoUrl`/`photoThumbnailUrl` 이 브라우저에서 200 으로 열리는지 확인.
