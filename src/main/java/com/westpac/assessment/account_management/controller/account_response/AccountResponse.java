package com.westpac.assessment.account_management.controller.account_response;

import com.westpac.assessment.account_management.domain.model.Account;
import java.io.Serializable;
import java.util.UUID;

public record AccountResponse(
    UUID id, String accountNumber, String customerName, String accountNickName)
    implements Serializable {
  public static AccountResponse from(Account account) {
    return new AccountResponse(
        account.getId(),
        account.getAccountNumber(),
        account.getCustomerName(),
        account.getAccountNickName());
  }
}
