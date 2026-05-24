package com.wherewego.domain.notification;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Phase 10: VISIT_DETECTED 알림 1행을 receiver 단위 새 트랜잭션(REQUIRES_NEW)으로 INSERT 한다.
 *
 * <p>{@link NotificationService#createForVisitDetected} 가 fan-out 루프에서 본 컴포넌트를 호출한다.
 * 부분 UNIQUE 인덱스 {@code uq_notifications_visit} 위반은 {@link DataIntegrityViolationException}
 * 으로 호출자에게 전파되며, 호출자에서 catch 하여 조용히 스킵한다 (race-free 중복 차단, BR-VD-1/2).</p>
 *
 * <p>Spring 트랜잭션 모델: REQUIRES_NEW 메서드 내부에서 예외를 catch 하면 트랜잭션이
 * rollback-only 로 마킹된 채 commit 단계에서 {@code UnexpectedRollbackException} 이 발생한다.
 * 따라서 catch 는 트랜잭션 경계 밖(=호출자)에서 수행해야 한다.</p>
 *
 * <p>Spring self-invocation 회피를 위해 별도 컴포넌트로 분리: 같은 클래스 내에서 @Transactional
 * 메서드를 호출하면 프록시를 거치지 않아 격리가 깨진다.</p>
 */
@Component
@RequiredArgsConstructor
public class NotificationVisitWriter {

    private final NotificationRepository repository;

    /**
     * receiver 1명에 대한 VISIT_DETECTED 알림 + NotificationPin 링크 1행 INSERT.
     * 부분 UNIQUE 인덱스 위반 시 {@link DataIntegrityViolationException} 이 그대로 호출자에게 전파된다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeOne(Long groupId, Long receiverId, Long registeredBy, Long pinId) {
        Notification n = repository.save(
                Notification.createForVisit(groupId, receiverId, registeredBy, pinId));
        repository.saveAllPins(List.of(NotificationPin.link(n.getId(), pinId, 0)));
    }
}
