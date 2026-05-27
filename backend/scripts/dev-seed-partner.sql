-- ============================================================
-- dev-seed-partner.sql
-- 목적: 로컬 dev DB 에서 "혼자 그룹" 상태인 사용자에게 가짜 2번째 멤버를 붙여
--       2인 그룹(WANT→WISH 자동전환, REEL+관심 시각화 등) UX 를 테스트할 수 있게 한다.
--
-- 사용 예 (psql 직접):
--   psql "postgres://wherewego:wherewego@localhost:5432/wherewego" -f backend/scripts/dev-seed-partner.sql
--
-- 사용 예 (docker compose 컨테이너):
--   docker compose exec db psql -U wherewego -d wherewego -f /scripts/dev-seed-partner.sql
--
-- 멱등: 여러 번 실행해도 dev_partner 사용자/멤버십이 중복 생성되지 않는다.
-- 롤백: scripts/dev-unseed-partner.sql (본 디렉터리, 동일 마커 기반 정리).
--
-- 주의: production DB 에는 절대 실행하지 말 것. kakao_user_id=999999990 는 실제 카카오
--      OAuth 응답으로 들어올 수 없는 placeholder 이지만, 인증 우회 의도가 아니므로
--      dev/local profile 전용이다.
-- ============================================================

DO $$
DECLARE
  partner_id   BIGINT;
  target_group BIGINT;
  solo_count   INT;
BEGIN
  -- 1) dev_partner placeholder user 보장 (멱등)
  INSERT INTO users (kakao_user_id, nickname)
  VALUES (999999990, 'dev_partner')
  ON CONFLICT (kakao_user_id) DO NOTHING;

  SELECT id INTO partner_id
  FROM users
  WHERE kakao_user_id = 999999990;

  -- 2) 활성 멤버가 1명뿐인 그룹을 찾는다 (= 테스트 대상 솔로 그룹)
  SELECT COUNT(*) INTO solo_count
  FROM (
    SELECT group_id
    FROM group_members
    WHERE left_at IS NULL
    GROUP BY group_id
    HAVING COUNT(*) = 1
  ) s;

  IF solo_count = 0 THEN
    RAISE NOTICE '시드 대상 솔로 그룹이 없습니다. (이미 2인 이상이거나 그룹 자체가 없습니다)';
    RETURN;
  ELSIF solo_count > 1 THEN
    RAISE NOTICE '솔로 그룹이 % 개입니다. 가장 최근 한 곳에만 시드를 적용합니다.', solo_count;
  END IF;

  SELECT s.group_id INTO target_group
  FROM (
    SELECT gm.group_id, MAX(gm.joined_at) AS last_joined
    FROM group_members gm
    WHERE gm.left_at IS NULL
    GROUP BY gm.group_id
    HAVING COUNT(*) = 1
  ) s
  ORDER BY s.last_joined DESC
  LIMIT 1;

  -- 3) dev_partner 가 다른 활성 그룹에 이미 들어가 있으면 우선 정리
  --    (V001 의 partial UNIQUE INDEX uq_group_members_active_user 충돌 회피)
  UPDATE group_members
  SET left_at = now()
  WHERE user_id = partner_id
    AND left_at IS NULL
    AND group_id <> target_group;

  -- 4) 멱등 삽입: 이미 활성이면 skip
  IF NOT EXISTS (
    SELECT 1 FROM group_members
    WHERE group_id = target_group
      AND user_id  = partner_id
      AND left_at IS NULL
  ) THEN
    -- 과거에 탈퇴(left_at != NULL) 행이 남아있다면 재활성, 아니면 신규 insert
    UPDATE group_members
    SET left_at = NULL, updated_at = now()
    WHERE group_id = target_group AND user_id = partner_id;

    IF NOT FOUND THEN
      INSERT INTO group_members (group_id, user_id)
      VALUES (target_group, partner_id);
    END IF;

    RAISE NOTICE '✅ dev_partner(user_id=%) 를 group_id=% 에 추가했어요. 이제 WANT 를 눌러도 1/2 단계(REEL+관심) 에서 멈춥니다.', partner_id, target_group;
  ELSE
    RAISE NOTICE 'ℹ️  dev_partner 가 이미 group_id=% 에 활성 멤버입니다.', target_group;
  END IF;
END $$;
