package com.wherewego.domain.pin;

import com.wherewego.domain.place.PlaceSearchHit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PinService {

    private final PinRepository pinRepository;

    /**
     * 인스타그램 링크 단건 결과 기반 자동 등록.
     * UNIQUE 충돌 시 {@link org.springframework.dao.DataIntegrityViolationException} 그대로 propagate.
     */
    @Transactional
    public Pin registerFromInstagram(Long userId, Long groupId, PlaceSearchHit hit, String instagramUrl) {
        Pin pin = Pin.autoFromInstagram(groupId, userId, hit, instagramUrl);
        return pinRepository.save(pin);
    }

    /**
     * 후보 카드 선택 기반 등록. UNIQUE 충돌 시 동일하게 propagate.
     */
    @Transactional
    public Pin registerFromSelection(Long userId, Long groupId, PlaceSearchHit hit, String instagramUrl) {
        Pin pin = Pin.fromSelection(groupId, userId, hit, instagramUrl);
        return pinRepository.save(pin);
    }
}
