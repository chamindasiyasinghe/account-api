package com.westpac.assessment.account_management.controller;

import static com.westpac.assessment.constants.ApiConstants.ACCOUNT_ITEM_PREFIX;

import com.westpac.assessment.account_management.controller.account_request.CreateAccountRequest;
import com.westpac.assessment.account_management.controller.account_response.AccountResponse;
import com.westpac.assessment.account_management.domain.service.AccountService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController implements AccountApi {
  private final AccountService service;

  public AccountController(AccountService service) {
    this.service = service;
  }

  @Override
  public ResponseEntity<AccountResponse> createAccount(
      CreateAccountRequest request, String idempotencyKey, Jwt jwt) {
    var account = service.createAccount(request, idempotencyKey, jwt.getSubject());
    return ResponseEntity.created(URI.create(ACCOUNT_ITEM_PREFIX + account.id())).body(account);
  }

  @Override
  public ResponseEntity<AccountResponse> getAccount(UUID id, Jwt jwt) {
    return ResponseEntity.ok(service.getAccount(id, jwt.getSubject()));
  }
}
