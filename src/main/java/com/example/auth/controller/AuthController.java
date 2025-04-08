package com.example.auth.controller;

import com.example.auth.common.ApiResponse;
import com.example.auth.dto.KakaoAuthRequest;
import com.example.auth.dto.KakaoTokenResponse;
import com.example.auth.dto.KakaoUserInfo;
import com.example.auth.dto.RefreshTokenRequest;
import com.example.auth.domain.User;
import com.example.auth.dto.UserLoginResult;
import com.example.auth.exception.JwtValidationException;
import com.example.auth.exception.TokenErrorType;
import com.example.auth.exception.TokenRefreshException;
import com.example.auth.service.KakaoService;
import com.example.auth.service.TokenService;
import com.example.auth.service.UserService;
import com.example.auth.security.JwtUtil;
import com.example.auth.util.TokenUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "인증 API", description = "로그인 / 로그아웃 / 재발급 관련 API")
public class AuthController {

    private final KakaoService kakaoService;
    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final TokenService tokenService;
    private final TokenUtils tokenUtils;

    @Value("${kakao.client-id}")
    private String kakaoClientId;

    @Operation(summary = "카카오 로그인", description = "인가코드와 리다이렉트 URI를 전달받아 JWT를 발급합니다.")
    @PostMapping("/kakao")
    public ResponseEntity<?> kakaoLogin(@RequestBody @Valid KakaoAuthRequest request) {
        log.info("카카오 로그인 요청: redirectUri={}", request.getRedirectUri());

        // 허용된 리다이렉트 URI인지 검증
        List<String> allowedUris = List.of(
                "http://localhost:3000/login/oauth2/code/kakao",
                "https://ckok.kr/login/oauth2/code/kakao"
        );

        if (!allowedUris.contains(request.getRedirectUri())) {
            log.warn("허용되지 않은 redirectUri: {}", request.getRedirectUri());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.fail("허용되지 않은 redirectUri입니다.", "VALIDATION_ERROR", HttpStatus.BAD_REQUEST.value()));
        }

        try {
            KakaoTokenResponse kakaoToken = kakaoService.requestToken(request.getAuthorizationCode(), request.getRedirectUri());
            KakaoUserInfo userInfo = kakaoService.requestUserInfo(kakaoToken.accessToken());
            UserLoginResult result = userService.findOrCreateUser("kakao", userInfo);
            User user = result.user();
            String loginType = result.isNew() ? "registration" : "login";

            String accessToken = jwtUtil.createAccessToken(user.getId());
            String refreshToken = jwtUtil.createRefreshToken(user.getId());
            tokenService.saveRefreshToken(user.getId(), refreshToken);

            log.info("카카오 로그인 성공: userId={}, loginType={}", user.getId(), loginType);

            Map<String, Object> responseData = Map.of(
                    "loginType", loginType,
                    "accessToken", accessToken,
                    "refreshToken", refreshToken,
                    "user", Map.of(
                            "id", user.getId(),
                            "nickname", user.getNickname(),
                            "email", user.getEmail(),
                            "profileImage", user.getProfileImg(),
                            "role", user.getRole()
                    )
            );

            return ResponseEntity.ok(ApiResponse.success(responseData, "카카오 로그인 성공"));
        } catch (Exception e) {
            log.error("카카오 로그인 처리 중 오류: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail("카카오 로그인 처리 중 오류가 발생했습니다.", "INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR.value()));
        }
    }

    @Operation(summary = "로그아웃", description = "accessToken을 블랙리스트 처리하고 refreshToken을 제거합니다.")
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader("Authorization") String bearerToken) {
        // 토큰 형식 확인
        if (bearerToken == null || !bearerToken.startsWith("Bearer ")) {
            log.warn("유효하지 않은 토큰 형식으로 로그아웃 시도");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.fail("유효하지 않은 토큰 형식입니다.", "UNAUTHORIZED", HttpStatus.UNAUTHORIZED.value()));
        }

        String token = bearerToken.replace("Bearer ", "");

        // 이미 블랙리스트된 토큰인지 확인
        if (tokenService.isBlacklisted(token)) {
            log.warn("이미 로그아웃된 토큰으로 로그아웃 시도");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.fail("이미 로그아웃된 토큰입니다.", "UNAUTHORIZED", HttpStatus.UNAUTHORIZED.value()));
        }

        try {
            // 토큰 유효성 검증 및 사용자 ID 추출
            Claims claims = jwtUtil.getClaims(token);
            Long userId = Long.valueOf(claims.getSubject());
            long remainTime = claims.getExpiration().getTime() - System.currentTimeMillis();

            // 토큰 블랙리스트 처리 및 리프레시 토큰 제거
            tokenService.blacklistAccessToken(token, remainTime);
            tokenService.deleteRefreshToken(userId);

            log.info("로그아웃 완료: userId={}", userId);

            return ResponseEntity.ok(ApiResponse.success(null, "로그아웃 완료"));
        } catch (ExpiredJwtException e) {
            log.warn("만료된 토큰으로 로그아웃 시도: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.fail("만료된 토큰입니다.", "TOKEN_EXPIRED", HttpStatus.UNAUTHORIZED.value()));
        } catch (JwtValidationException e) {
            log.warn("JWT 검증 오류 - 로그아웃: {}, 타입: {}", e.getMessage(), e.getErrorType());

            String errorCode = "UNAUTHORIZED";
            if (e.getErrorType() == TokenErrorType.EXPIRED) {
                errorCode = "TOKEN_EXPIRED";
            }

            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.fail(e.getMessage(), errorCode, HttpStatus.UNAUTHORIZED.value()));
        } catch (Exception e) {
            log.error("로그아웃 처리 중 오류: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.fail("유효하지 않은 토큰입니다.", "UNAUTHORIZED", HttpStatus.UNAUTHORIZED.value()));
        }
    }

    @Operation(summary = "카카오 로그인 리다이렉트", description = "프론트에서 받은 redirectUri를 기반으로 카카오 로그인 페이지로 직접 리다이렉트합니다.")
    @GetMapping("/login-redirect")
    public void redirectToKakaoLogin(
            @RequestParam("redirectUri") String redirectUri,
            HttpServletResponse response
    ) throws IOException {
        List<String> allowedUris = List.of(
                "http://localhost:3000/login/oauth2/code/kakao",
                "https://ckok.kr/login/oauth2/code/kakao"
        );

        if (!allowedUris.contains(redirectUri)) {
            log.warn("허용되지 않은 redirectUri: {}", redirectUri);
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "허용되지 않은 redirectUri입니다.");
            return;
        }

        String kakaoUrl = UriComponentsBuilder
                .fromUriString("https://kauth.kakao.com/oauth/authorize")
                .queryParam("client_id", kakaoClientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .build().toUriString();

        log.info("카카오 로그인 페이지로 리다이렉트: redirectUri={}", redirectUri);
        response.sendRedirect(kakaoUrl);
    }

    @Operation(summary = "토큰 재발급", description = "accessToken과 refreshToken을 이용해 새 토큰을 발급합니다.")
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(
            @RequestHeader("Authorization") String bearerToken,
            @RequestBody @Valid RefreshTokenRequest request
    ) {
        log.info("토큰 재발급 요청");

        try {
            String accessToken = bearerToken.replace("Bearer ", "");

            // 토큰 만료 여부 상관없이 클레임 추출
            Claims claims;
            try {
                claims = jwtUtil.getClaimsIgnoreExpiration(accessToken);
            } catch (Exception e) {
                log.warn("액세스 토큰 파싱 실패: {}", e.getMessage());
                throw new TokenRefreshException("유효하지 않은 액세스 토큰입니다.", "UNAUTHORIZED");
            }

            Long userId = Long.valueOf(claims.getSubject());

            // 블랙리스트 체크
            if (tokenService.isBlacklisted(accessToken)) {
                log.warn("블랙리스트에 있는 토큰으로 재발급 시도: userId={}", userId);
                throw new TokenRefreshException("로그아웃된 토큰입니다.", "UNAUTHORIZED");
            }

            // Redis에 저장된 리프레시 토큰 검증
            String savedRefresh = tokenService.getRefreshToken(userId);
            if (savedRefresh == null) {
                log.warn("저장된 리프레시 토큰 없음: userId={}", userId);
                throw new TokenRefreshException("리프레시 토큰이 만료되었습니다.", "INVALID_REFRESH_TOKEN");
            }

            if (!request.getRefreshToken().equals(savedRefresh)) {
                log.warn("유효하지 않은 리프레시 토큰: userId={}", userId);
                throw new TokenRefreshException("리프레시 토큰이 유효하지 않습니다.", "INVALID_REFRESH_TOKEN");
            }

            // 새 토큰 발급
            String newAccess = jwtUtil.createAccessToken(userId);
            String newRefresh = jwtUtil.createRefreshToken(userId);
            tokenService.saveRefreshToken(userId, newRefresh);

            log.info("토큰 재발급 성공: userId={}", userId);

            Map<String, Object> data = Map.of(
                    "accessToken", newAccess,
                    "refreshToken", newRefresh
            );

            return ResponseEntity.ok(ApiResponse.success(data, "토큰이 성공적으로 재발급되었습니다."));
        } catch (TokenRefreshException e) {
            if ("INVALID_REFRESH_TOKEN".equals(e.getErrorCode())) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.fail(e.getMessage(), "INVALID_REFRESH_TOKEN", HttpStatus.UNAUTHORIZED.value()));
            } else {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ApiResponse.fail(e.getMessage(), e.getErrorCode(), HttpStatus.UNAUTHORIZED.value()));
            }
        } catch (Exception e) {
            log.error("토큰 재발급 중 오류 발생: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.fail("토큰 재발급 중 오류가 발생했습니다.", "TOKEN_REFRESH_ERROR", HttpStatus.INTERNAL_SERVER_ERROR.value()));
        }
    }
}