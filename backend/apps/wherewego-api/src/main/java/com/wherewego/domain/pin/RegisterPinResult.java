package com.wherewego.domain.pin;

/**
 * {@link PinService#registerFromInstagramWithDedup} 결과.
 *
 * <p>{@code alreadyExisted=true} 이면 {@code pin} 은 이미 존재하던 핀으로 새로 저장된 것이 아니며
 * memo 갱신 없이 호출자에게 반환된다. {@code false} 이면 새로 저장된 핀이다.</p>
 */
public record RegisterPinResult(Pin pin, boolean alreadyExisted) { }
