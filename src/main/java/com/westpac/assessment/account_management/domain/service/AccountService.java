package com.westpac.assessment.account_management.domain.service;

import com.westpac.assessment.account_management.controller.account_request.CreateAccountRequest;
import com.westpac.assessment.account_management.controller.account_response.AccountResponse;
import com.westpac.assessment.account_management.domain.exceptions.BusinessException;
import com.westpac.assessment.account_management.domain.model.*;
import com.westpac.assessment.account_management.domain.repository.*;
import com.westpac.assessment.account_management.domain.validator.OffensiveNicknameValidator;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService {
  private final AccountRepository repository;
  private final IdempotencyRepository idempotency;
  private final CustomerAccountLock lock;
  private final OffensiveNicknameValidator nicknameValidator;

  public AccountService(
      AccountRepository repository,
      IdempotencyRepository idempotency,
      CustomerAccountLock lock,
      OffensiveNicknameValidator nicknameValidator) {
    this.repository = repository;
    this.idempotency = idempotency;
    this.lock = lock;
    this.nicknameValidator = nicknameValidator;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public AccountResponse createAccount(
      CreateAccountRequest request, String idempotencyKey, String owner) {
    String fingerprint = fingerprint(request);
    // Length-delimited principal prevents ambiguity when subjects/keys contain separators.
    lock.acquire("idempotency:" + owner.length() + ":" + owner + ":" + idempotencyKey);
    var previous = idempotency.findByOwnerSubjectAndIdempotencyKey(owner, idempotencyKey);
    if (previous.isPresent()) {
      if (!previous.get().getRequestHash().equals(fingerprint)) {
        throw new BusinessException(
            "Idempotency-Key has already been used with a different request");
      }
      return AccountResponse.from(
          repository
              .findById(previous.get().getAccountId())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Idempotency record references a missing account")));
    }
    nicknameValidator.validate(request.accountNickName());
    // TODO: Replace name-based customer identity with a verified customer ID in a real API.
    String customerKey = request.customerName().toLowerCase(Locale.ROOT);
    lock.acquire("customer:" + customerKey);
    if (repository.countByCustomerKey(customerKey) >= 5) {
      throw new BusinessException("A customer cannot create more than 5 accounts");
    }
    Account account =
        repository.saveAndFlush(
            new Account(request.customerName(), customerKey, request.accountNickName(), owner));
    idempotency.saveAndFlush(
        new IdempotencyRecord(owner, idempotencyKey, fingerprint, account.getId()));
    return AccountResponse.from(account);
  }

  @Transactional(readOnly = true)
  @Cacheable(cacheNames = "accounts", key = "#p0.toString() + ':' + #p1")
  public AccountResponse getAccount(UUID id, String owner) {
    Account account =
        repository
            .findById(id)
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Savings account not found"));
    if (!owner.equals(account.getOwnerSubject()))
      throw new AccessDeniedException("Account access denied");
    return AccountResponse.from(account);
  }

  private String fingerprint(CreateAccountRequest request) {
    String name = request.customerName();
    String nick = request.accountNickName();
    String canonical =
        name.length() + ":" + name + ":" + (nick == null ? "null" : nick.length() + ":" + nick);
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
