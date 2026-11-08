package com.westpac.assessment.account_management.controller;

import static com.westpac.assessment.constants.ApiConstants.ACCOUNTS_PATH;
import static com.westpac.assessment.constants.ApiConstants.ACCOUNT_BY_ID_PATH;
import static com.westpac.assessment.constants.ApiConstants.IDEMPOTENCY_KEY_HEADER;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

import com.westpac.assessment.account_management.controller.account_request.CreateAccountRequest;
import com.westpac.assessment.account_management.controller.account_response.AccountResponse;
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Savings accounts")
public interface AccountApi {
  @Operation(
      summary = "Create a savings account",
      description =
          "Requires accounts:write scope. Nicknames are screened against the configured"
              + " offensive-name list; prohibited names return 422. Repeating the same"
              + " Idempotency-Key and body for the same JWT subject returns the original account"
              + " with 201 and the same Location. A changed body with the same key returns 422.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Account created or original creation replayed",
        content =
            @Content(
                mediaType = APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = AccountResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "Invalid request body or Idempotency-Key",
        content =
            @Content(
                mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "422",
        description =
            "Account limit, offensive nickname, idempotency conflict or integrity violation",
        content =
            @Content(
                mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "401",
        description = "Missing or invalid Bearer JWT or api_key",
        content =
            @Content(
                mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "403",
        description = "Insufficient permissions or account ownership",
        content =
            @Content(
                mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "500",
        description = "Unexpected internal code error",
        content =
            @Content(
                mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "503",
        description = "Required database, cache or upstream service unavailable",
        content =
            @Content(
                mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class)))
  })
  @PostMapping(
      value = ACCOUNTS_PATH,
      consumes = APPLICATION_JSON_VALUE,
      produces = APPLICATION_JSON_VALUE)
  ResponseEntity<AccountResponse> createAccount(
      @Valid @RequestBody CreateAccountRequest request,
      @Parameter(
              description =
                  "Unique client-generated key per intended creation. Reuse unchanged on retries.",
              required = true,
              example = "0d19ede4-cc96-44bb-b8e3-01ed2e214955",
              schema = @Schema(minLength = 1, maxLength = 128))
          @NotBlank
          @Size(max = 128)
          @RequestHeader(IDEMPOTENCY_KEY_HEADER)
          String idempotencyKey,
      @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt);

  @Operation(
      summary = "Get a savings account by ID",
      description =
          "Requires accounts:read scope and the same JWT subject that created the account.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Account found",
        content =
            @Content(
                mediaType = APPLICATION_JSON_VALUE,
                schema = @Schema(implementation = AccountResponse.class))),
    @ApiResponse(
        responseCode = "400",
        description = "Invalid account UUID",
        content =
            @Content(
                mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "404",
        description = "Account not found",
        content =
            @Content(
                mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "401",
        description = "Missing or invalid Bearer JWT or api_key",
        content =
            @Content(
                mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "403",
        description = "Insufficient permissions or account ownership",
        content =
            @Content(
                mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "500",
        description = "Unexpected internal code error",
        content =
            @Content(
                mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class))),
    @ApiResponse(
        responseCode = "503",
        description = "Required database, cache or upstream service unavailable",
        content =
            @Content(
                mediaType = APPLICATION_PROBLEM_JSON_VALUE,
                schema = @Schema(implementation = ProblemDetail.class)))
  })
  @GetMapping(value = ACCOUNT_BY_ID_PATH, produces = APPLICATION_JSON_VALUE)
  ResponseEntity<AccountResponse> getAccount(
      @PathVariable("id") UUID id, @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt);
}
