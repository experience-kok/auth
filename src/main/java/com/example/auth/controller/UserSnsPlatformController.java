package com.example.auth.controller;

import com.example.auth.common.BaseResponse;
import com.example.auth.domain.User;
import com.example.auth.domain.UserSnsPlatform;
import com.example.auth.dto.SnsPlatformRequest;
import com.example.auth.common.ErrorResponseDTO;
import com.example.auth.common.PlatformListResponseDTO;
import com.example.auth.common.PlatformResponseDTO;
import com.example.auth.exception.JwtValidationException;
import com.example.auth.repository.UserRepository;
import com.example.auth.repository.UserSnsPlatformRepository;
import com.example.auth.util.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Tag(name = "SNS 플랫폼 API", description = "사용자의 SNS 플랫폼 관리 API")
@RestController
@RequestMapping("/api/users/platforms")
@RequiredArgsConstructor
public class UserSnsPlatformController {

    private final UserRepository userRepository;
    private final UserSnsPlatformRepository platformRepository;
    private final TokenUtils tokenUtils;

    @Operation(summary = "SNS 플랫폼 목록 조회", description = "로그인한 사용자의 등록된 SNS 플랫폼 목록을 조회합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "플랫폼 목록 조회 성공",
                    content = @Content(schema = @Schema(implementation = PlatformListResponseDTO.class))),
            @ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDTO.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDTO.class)))
    })

    @GetMapping
    public ResponseEntity<?> getPlatforms(
            @Parameter(description = "Bearer 토큰", required = true)
            @RequestHeader("Authorization") String bearerToken
    ) {
        try {
            Long userId = tokenUtils.getUserIdFromToken(bearerToken);

            List<UserSnsPlatform> platforms = platformRepository.findByUserId(userId);
            List<PlatformResponse> responseList = platforms.stream()
                    .map(this::mapToPlatformResponse)
                    .collect(Collectors.toList());

            log.info("SNS 플랫폼 목록 조회 성공: userId={}, 플랫폼 수={}", userId, responseList.size());

            return ResponseEntity.ok(
                    BaseResponse.success(
                            responseList,
                            "SNS 플랫폼 목록을 성공적으로 조회했습니다."
                    )
            );
        } catch (JwtValidationException e) {
            log.warn("인증 오류 - 플랫폼 목록 조회: {}, 오류 유형: {}", e.getMessage(), e.getErrorType());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(BaseResponse.fail(e.getMessage(), e.getErrorType().name(), HttpStatus.UNAUTHORIZED.value()));
        } catch (Exception e) {
            log.error("플랫폼 목록 조회 중 오류 발생: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(BaseResponse.fail("서버 오류가 발생했습니다.", "INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR.value()));
        }
    }
    @Operation(summary = "SNS 플랫폼 등록", description = "새로운 SNS 플랫폼을 등록합니다. verified는 항상 false로 저장됩니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "플랫폼 등록 성공",
                    content = @Content(schema = @Schema(implementation = PlatformResponseDTO.class))),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 요청 또는 중복 등록",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDTO.class))),
            @ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDTO.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDTO.class)))
    })


    @PostMapping
    public ResponseEntity<?> addPlatform(
            @Parameter(description = "Bearer 토큰", required = true)
            @RequestHeader("Authorization") String bearerToken,
            @Parameter(description = "등록할 SNS 플랫폼 정보", required = true)
            @RequestBody @Valid SnsPlatformRequest request
    ) {
        try {
            Long userId = tokenUtils.getUserIdFromToken(bearerToken);
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("사용자 정보를 찾을 수 없습니다."));

            // 이미 등록된 플랫폼 체크
            platformRepository.findByUserIdAndPlatformTypeAndAccountUrl(userId, request.getPlatformType(), request.getAccountUrl())
                    .ifPresent(platform -> {
                        throw new RuntimeException("이미 등록된 SNS 플랫폼입니다.");
                    });

            UserSnsPlatform platform = UserSnsPlatform.builder()
                    .user(user)
                    .platformType(request.getPlatformType())
                    .accountUrl(request.getAccountUrl())
                    .accountName(request.getAccountName())
                    .verified(false) // 항상 false로 설정
                    .build();

            UserSnsPlatform savedPlatform = platformRepository.save(platform);
            log.info("SNS 플랫폼 등록 완료: userId={}, platformType={}", userId, request.getPlatformType());

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(
                            BaseResponse.success(
                                    mapToPlatformResponse(savedPlatform),
                                    "SNS 플랫폼이 성공적으로 등록되었습니다.",
                                    HttpStatus.CREATED.value()
                            )
                    );
        } catch (JwtValidationException e) {
            log.warn("인증 오류 - 플랫폼 등록: {}, 오류 유형: {}", e.getMessage(), e.getErrorType());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(BaseResponse.fail(e.getMessage(), e.getErrorType().name(), HttpStatus.UNAUTHORIZED.value()));
        } catch (RuntimeException e) {
            // 중복 등록 등의 런타임 예외
            log.warn("플랫폼 등록 실패: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(BaseResponse.fail(e.getMessage(), "VALIDATION_ERROR", HttpStatus.BAD_REQUEST.value()));
        } catch (Exception e) {
            log.error("플랫폼 등록 중 오류 발생: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(BaseResponse.fail("서버 오류가 발생했습니다.", "INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR.value()));
        }
    }

    @Operation(summary = "SNS 플랫폼 조회", description = "특정 SNS 플랫폼 정보를 조회합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "플랫폼 조회 성공",
                    content = @Content(schema = @Schema(implementation = PlatformResponseDTO.class))),
            @ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDTO.class))),
            @ApiResponse(responseCode = "404", description = "플랫폼을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDTO.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDTO.class)))
    })



    @GetMapping("/{platformId}")
    public ResponseEntity<?> getPlatform(
            @Parameter(description = "Bearer 토큰", required = true)
            @RequestHeader("Authorization") String bearerToken,
            @Parameter(description = "조회할 SNS 플랫폼 ID", required = true)
            @PathVariable Long platformId
    ) {
        try {
            Long userId = tokenUtils.getUserIdFromToken(bearerToken);

            UserSnsPlatform platform = platformRepository.findByUserIdAndId(userId, platformId)
                    .orElseThrow(() -> new RuntimeException("등록된 SNS 플랫폼을 찾을 수 없습니다."));

            log.info("SNS 플랫폼 조회 성공: userId={}, platformId={}", userId, platformId);

            return ResponseEntity.ok(
                    BaseResponse.success(
                            mapToPlatformResponse(platform),
                            "SNS 플랫폼 정보를 성공적으로 조회했습니다."
                    )
            );
        } catch (JwtValidationException e) {
            log.warn("인증 오류 - 플랫폼 조회: {}, 오류 유형: {}", e.getMessage(), e.getErrorType());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(BaseResponse.fail(e.getMessage(), e.getErrorType().name(), HttpStatus.UNAUTHORIZED.value()));
        } catch (RuntimeException e) {
            // 플랫폼 조회 실패
            log.warn("플랫폼 조회 실패: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(BaseResponse.fail(e.getMessage(), "RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND.value()));
        } catch (Exception e) {
            log.error("플랫폼 조회 중 오류 발생: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(BaseResponse.fail("서버 오류가 발생했습니다.", "INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR.value()));
        }
    }
    @Operation(summary = "SNS 플랫폼 수정", description = "등록된 SNS 플랫폼 정보를 수정합니다. verified는 수정할 수 없습니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "플랫폼 수정 성공",
                    content = @Content(schema = @Schema(implementation = PlatformResponseDTO.class))),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 요청",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDTO.class))),
            @ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDTO.class))),
            @ApiResponse(responseCode = "404", description = "플랫폼을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDTO.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDTO.class)))
    })

    @PutMapping("/{platformId}")
    public ResponseEntity<?> updatePlatform(
            @Parameter(description = "Bearer 토큰", required = true)
            @RequestHeader("Authorization") String bearerToken,
            @Parameter(description = "수정할 SNS 플랫폼 ID", required = true)
            @PathVariable Long platformId,
            @Parameter(description = "수정할 SNS 플랫폼 정보", required = true)
            @RequestBody @Valid SnsPlatformRequest request
    ) {
        try {
            Long userId = tokenUtils.getUserIdFromToken(bearerToken);

            UserSnsPlatform platform = platformRepository.findByUserIdAndId(userId, platformId)
                    .orElseThrow(() -> new RuntimeException("등록된 SNS 플랫폼을 찾을 수 없습니다."));

            platform.updateAccountName(request.getAccountName());
            platform.updateAccountUrl(request.getAccountUrl());

            // verified 필드는 일반 사용자 수정 불가 → 무시

            UserSnsPlatform updatedPlatform = platformRepository.save(platform);
            log.info("SNS 플랫폼 수정 완료: userId={}, platformId={}", userId, platformId);

            return ResponseEntity.ok(
                    BaseResponse.success(
                            mapToPlatformResponse(updatedPlatform),
                            "SNS 플랫폼 정보가 성공적으로 수정되었습니다."
                    )
            );
        } catch (JwtValidationException e) {
            log.warn("인증 오류 - 플랫폼 수정: {}, 오류 유형: {}", e.getMessage(), e.getErrorType());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(BaseResponse.fail(e.getMessage(), e.getErrorType().name(), HttpStatus.UNAUTHORIZED.value()));
        } catch (RuntimeException e) {
            // 플랫폼 수정 실패
            log.warn("플랫폼 수정 실패: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(BaseResponse.fail(e.getMessage(), "RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND.value()));
        } catch (Exception e) {
            log.error("플랫폼 수정 중 오류 발생: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(BaseResponse.fail("서버 오류가 발생했습니다.", "INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR.value()));
        }
    }

    @Operation(summary = "SNS 플랫폼 삭제", description = "등록된 SNS 플랫폼을 삭제합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "플랫폼 삭제 성공",
                    content = @Content(schema = @Schema(implementation = BaseResponse.Success.class))),
            @ApiResponse(responseCode = "401", description = "인증 실패",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDTO.class))),
            @ApiResponse(responseCode = "404", description = "플랫폼을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDTO.class))),
            @ApiResponse(responseCode = "500", description = "서버 오류",
                    content = @Content(schema = @Schema(implementation = ErrorResponseDTO.class)))
    })

    @DeleteMapping("/{platformId}")
    public ResponseEntity<?> deletePlatform(
            @Parameter(description = "Bearer 토큰", required = true)
            @RequestHeader("Authorization") String bearerToken,
            @Parameter(description = "삭제할 SNS 플랫폼 ID", required = true)
            @PathVariable Long platformId
    ) {
        try {
            Long userId = tokenUtils.getUserIdFromToken(bearerToken);

            UserSnsPlatform platform = platformRepository.findByUserIdAndId(userId, platformId)
                    .orElseThrow(() -> new RuntimeException("등록된 SNS 플랫폼을 찾을 수 없습니다."));

            platformRepository.delete(platform);
            log.info("SNS 플랫폼 삭제 완료: userId={}, platformId={}", userId, platformId);

            return ResponseEntity.ok(
                    BaseResponse.success(
                            null,
                            "SNS 플랫폼이 성공적으로 삭제되었습니다."
                    )
            );
        } catch (JwtValidationException e) {
            log.warn("인증 오류 - 플랫폼 삭제: {}, 오류 유형: {}", e.getMessage(), e.getErrorType());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(BaseResponse.fail(e.getMessage(), e.getErrorType().name(), HttpStatus.UNAUTHORIZED.value()));
        } catch (RuntimeException e) {
            // 플랫폼 삭제 실패
            log.warn("플랫폼 삭제 실패: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(BaseResponse.fail(e.getMessage(), "RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND.value()));
        } catch (Exception e) {
            log.error("플랫폼 삭제 중 오류 발생: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(BaseResponse.fail("서버 오류가 발생했습니다.", "INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR.value()));
        }
    }

    private PlatformResponse mapToPlatformResponse(UserSnsPlatform platform) {
        return new PlatformResponse(
                platform.getId(),
                platform.getPlatformType(),
                platform.getAccountUrl(),
                platform.getAccountName(),
                platform.getVerified()
        );
    }

    public record PlatformResponse(
            Long id,
            String platformType,
            String accountUrl,
            String accountName,
            Boolean verified
    ) {}
}