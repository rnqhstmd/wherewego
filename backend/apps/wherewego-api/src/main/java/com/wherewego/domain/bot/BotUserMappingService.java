package com.wherewego.domain.bot;

import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BotUserMappingService {

    private final BotUserMappingRepository mappingRepository;
    private final BotLinkCodeService linkCodeService;

    @Transactional
    public BotUserLinkResult link(String code, String botUserKey, Instant now) {
        if (mappingRepository.findByBotUserKey(botUserKey).isPresent()) {
            throw new CoreException(ErrorType.BOT_USER_ALREADY_LINKED);
        }

        // M6: peekUserId 로 단일 read — 이전엔 linkCodeRepository.findByCode + consumeCode 내부 findByCode 로 2회 조회됐다.
        Long userId = linkCodeService.peekUserId(code);

        if (mappingRepository.findByUserId(userId).isPresent()) {
            throw new CoreException(ErrorType.BOT_USER_ALREADY_LINKED);
        }

        BotLinkCodeConsumeResult consumed = linkCodeService.consumeCode(code, now);

        try {
            BotUserMapping mapping = mappingRepository.save(
                    BotUserMapping.link(botUserKey, consumed.userId(), now)
            );
            return new BotUserLinkResult(mapping.getUserId(), mapping.getBotUserKey(), mapping.getLinkedAt());
        } catch (DataIntegrityViolationException e) {
            // TOCTOU 경쟁 조건: SELECT 통과 후 동시 INSERT 로 UNIQUE 제약 충돌.
            throw new CoreException(ErrorType.BOT_USER_ALREADY_LINKED);
        }
    }

    @Transactional(readOnly = true)
    public Optional<Long> resolveUserId(String botUserKey) {
        return mappingRepository.findByBotUserKey(botUserKey).map(BotUserMapping::getUserId);
    }

    /**
     * 사용자의 봇 연동을 해제한다 (B-4). 매핑이 없으면 멱등 skip.
     * <p>
     * <strong>호출 계약:</strong> 반드시 호출자의 트랜잭션 안에서 실행되어야 한다
     * (예: {@link com.wherewego.domain.group.GroupMemberService#leaveGroup}).
     * MANDATORY 전파로 부모 TX에 합류한다. 부모 TX 없이 단독 호출 시 IllegalTransactionStateException 발생. AC-B8(GroupMember soft-delete와 동일 TX) 보장.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void unlink(Long userId) {
        mappingRepository.deleteByUserId(userId);
    }
}
