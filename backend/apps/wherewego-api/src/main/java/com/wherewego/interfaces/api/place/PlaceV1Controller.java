package com.wherewego.interfaces.api.place;

import com.wherewego.config.security.AuthUser;
import com.wherewego.domain.place.PlaceSearchOutcome;
import com.wherewego.domain.place.PlaceSearchService;
import com.wherewego.interfaces.api.ApiResponse;
import com.wherewego.support.error.CoreException;
import com.wherewego.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/places")
@RequiredArgsConstructor
public class PlaceV1Controller implements PlaceV1ApiSpec {

    private final PlaceSearchService placeSearchService;

    @GetMapping("/search")
    @Override
    public ApiResponse<PlaceV1Dto.PlaceSearchResponse> search(
            @AuthUser Long userId,
            @RequestParam("q") String keyword
    ) {
        if (keyword == null || keyword.isBlank()) {
            throw new CoreException(ErrorType.PLACE_SEARCH_KEYWORD_INVALID);
        }
        PlaceSearchOutcome outcome = placeSearchService.searchByKeyword(keyword);
        return ApiResponse.success(PlaceV1Dto.PlaceSearchResponse.from(outcome));
    }
}
