package com.wherewego.infrastructure.place.google;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.wherewego.config.cache.CacheConfig;
import com.wherewego.domain.place.PlaceSearchHit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link GooglePlacesResponseCacheService} 단위 검증.
 *
 * <p>get/put 히트, 빈 리스트 캐싱, hashKey 동일성, List.copyOf immutability 를 검증한다.</p>
 */
@DisplayName("GooglePlacesResponseCacheService 는,")
class GooglePlacesResponseCacheServiceTest {

    private CaffeineCacheManager cacheManager;
    private GooglePlacesResponseCacheService service;

    @BeforeEach
    void setUp() {
        cacheManager = new CaffeineCacheManager();
        cacheManager.registerCustomCache(
                CacheConfig.GOOGLE_PLACES_RESPONSE_CACHE,
                Caffeine.newBuilder().build()
        );
        service = new GooglePlacesResponseCacheService(cacheManager);
    }

    private static PlaceSearchHit hit(String id) {
        return new PlaceSearchHit(id, "스타벅스", "서울시 강남구", 37.5, 127.0);
    }

    @DisplayName("최초 조회 시 miss → put 후 동일 키 조회 시 히트한다.")
    @Test
    void missThenPutThenHit() {
        // arrange
        String key = service.hashKey("스타벅스");

        // act
        Optional<List<PlaceSearchHit>> beforePut = service.get(key);
        service.put(key, List.of(hit("a")));
        Optional<List<PlaceSearchHit>> afterPut = service.get(key);

        // assert
        assertThat(beforePut).isEmpty();
        assertThat(afterPut).isPresent();
        assertThat(afterPut.get()).hasSize(1);
        assertThat(afterPut.get().get(0).kakaoPlaceId()).isEqualTo("a");
    }

    @DisplayName("(AC-3) 빈 List.of() 도 캐싱되어 후속 조회 시 hit 으로 반환된다.")
    @Test
    void emptyListAlsoCached() {
        // arrange
        String key = service.hashKey("없는장소");

        // act
        service.put(key, List.of());
        Optional<List<PlaceSearchHit>> result = service.get(key);

        // assert
        assertThat(result).isPresent();
        assertThat(result.get()).isEmpty();
    }

    @DisplayName("동일 키워드는 hashKey 가 동일하다.")
    @Test
    void hashKey_sameKeyword_sameHash() {
        // act
        String h1 = service.hashKey("스타벅스 강남점");
        String h2 = service.hashKey("스타벅스 강남점");

        // assert
        assertThat(h1).isEqualTo(h2).isNotBlank();
    }

    @DisplayName("put 후 원본 리스트를 외부에서 변형해도 캐시 내부에는 영향이 없다 (List.copyOf immutability).")
    @Test
    void put_listCopyOfImmutability_externalMutationDoesNotAffectCache() {
        // arrange
        String key = service.hashKey("immutable");
        List<PlaceSearchHit> mutable = new ArrayList<>();
        mutable.add(hit("a"));

        // act
        service.put(key, mutable);
        mutable.add(hit("b")); // 외부 변형 시도
        Optional<List<PlaceSearchHit>> result = service.get(key);

        // assert
        assertThat(result).isPresent();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0).kakaoPlaceId()).isEqualTo("a");

        // 또한 get 으로 반환된 immutable 리스트는 add 호출 시 UnsupportedOperationException.
        assertThatThrownBy(() -> result.get().add(hit("c")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @DisplayName("put 에 null 을 전달하면 캐시에 적재하지 않는다 (가드).")
    @Test
    void put_nullHits_skips() {
        // arrange
        String key = service.hashKey("nullput");

        // act
        service.put(key, null);

        // assert
        assertThat(service.get(key)).isEmpty();
    }
}
