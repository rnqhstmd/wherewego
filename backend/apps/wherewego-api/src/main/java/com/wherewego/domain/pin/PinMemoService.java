package com.wherewego.domain.pin;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PinMemoService {

    private final PinRepository pinRepository;

    /**
     * 2초 윈도우 내 AUTO 메모를 조건부 UPDATE 한다.
     * <p>SELECT 단계 없음. {@code WHERE id=? AND created_by=? AND (memo_source IS NULL OR memo_source<>'MANUAL')}.</p>
     * <p>윈도우 검사는 호출자({@code TwoSecondMemoSession.peek})가 책임진다.</p>
     *
     * @return 갱신 성공 여부 (true = 1행 갱신, false = 이미 MANUAL/소유자 불일치)
     */
    @Transactional
    public boolean attachAutoMemoIfWithinWindow(Long pinId, Long ownerUserId, String memo) {
        return pinRepository.updateAutoMemoIfNotManual(pinId, ownerUserId, memo) > 0;
    }
}
