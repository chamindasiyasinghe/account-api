package com.westpac.assessment.account_management.controller.account_request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
    @NotBlank(message = "Customer name is required")
        @Size(max = 200, message = "Customer name must not exceed 200 characters")
        @Schema(example = "Chaminda Siyasinghe", requiredMode = Schema.RequiredMode.REQUIRED)
        String customerName,
    @Size(min = 5, max = 30, message = "Account nickname must be between 5 and 30 characters")
        @Schema(example = "Holiday savings", nullable = true)
        String accountNickName) {
  public CreateAccountRequest {
    if (customerName != null) customerName = customerName.strip().replaceAll("\\s+", " ");
    if (accountNickName != null) accountNickName = accountNickName.strip();
  }
}
