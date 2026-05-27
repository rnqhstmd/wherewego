-- ============================================================
-- dev-unseed-partner.sql
-- 목적: dev-seed-partner.sql 로 추가한 가짜 멤버(dev_partner)를 좌측 그룹에서 제거.
--       dev_partner 가 남긴 WANT/PinEvent 도 함께 정리한다.
--
-- 멱등: dev_partner 가 없거나 이미 탈퇴한 상태여도 안전.
-- ============================================================

DO $$
DECLARE
  partner_id BIGINT;
BEGIN
  SELECT id INTO partner_id
  FROM users
  WHERE kakao_user_id = 999999990;

  IF partner_id IS NULL THEN
    RAISE NOTICE 'dev_partner 사용자가 존재하지 않습니다. 할 일이 없어요.';
    RETURN;
  END IF;

  -- dev_partner 의 WANT 이력 제거 (다른 사용자가 누른 WANT 는 건드리지 않음)
  DELETE FROM pin_events
  WHERE user_id = partner_id
    AND action  = 'WANT';

  -- want_count 동기화: pin_events 에서 카운트를 재계산해 반영.
  UPDATE pins p
  SET want_count = sub.cnt
  FROM (
    SELECT pe.pin_id, COUNT(*)::INT AS cnt
    FROM pin_events pe
    WHERE pe.action = 'WANT'
    GROUP BY pe.pin_id
  ) sub
  WHERE p.id = sub.pin_id
    AND p.want_count <> sub.cnt;

  -- 모든 활성 멤버십 비활성화 (soft leave)
  UPDATE group_members
  SET left_at = now()
  WHERE user_id = partner_id
    AND left_at IS NULL;

  RAISE NOTICE '✅ dev_partner(user_id=%) 의 모든 그룹 멤버십과 WANT 를 정리했어요.', partner_id;
END $$;
