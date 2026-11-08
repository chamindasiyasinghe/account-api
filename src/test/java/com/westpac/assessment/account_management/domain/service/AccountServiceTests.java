package com.westpac.assessment.account_management.domain.service;

import static com.westpac.assessment.TestConstants.TEST_CUSTOMER_NAME;
import static com.westpac.assessment.TestConstants.TEST_OWNER;
import static com.westpac.assessment.TestConstants.TEST_REQUEST_KEY;
import static com.westpac.assessment.TestConstants.TEST_SUBJECT;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.westpac.assessment.account_management.controller.account_request.CreateAccountRequest;
import com.westpac.assessment.account_management.domain.exceptions.BusinessException;
import com.westpac.assessment.account_management.domain.model.*;
import com.westpac.assessment.account_management.domain.repository.*;
import com.westpac.assessment.account_management.domain.validator.OffensiveNicknameValidator;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AccountServiceTests {
  @Test
  void locksIdempotencyThenCustomerBeforeCountingAndSaving() {
    var repository = mock(AccountRepository.class);
    var idempotency = mock(IdempotencyRepository.class);
    var lock = mock(CustomerAccountLock.class);
    when(idempotency.findByOwnerSubjectAndIdempotencyKey(anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(repository.saveAndFlush(any(Account.class))).thenAnswer(call -> call.getArgument(0));
    new AccountService(repository, idempotency, lock, mock(OffensiveNicknameValidator.class))
        .createAccount(
            new CreateAccountRequest(" Alice  Smith ", null), TEST_REQUEST_KEY, TEST_OWNER);
    var order = inOrder(lock, idempotency, repository);
    order.verify(lock).acquire("idempotency:5:owner:key");
    order.verify(idempotency).findByOwnerSubjectAndIdempotencyKey(TEST_OWNER, TEST_REQUEST_KEY);
    order.verify(lock).acquire("customer:alice smith");
    order.verify(repository).countByCustomerKey("alice smith");
    order.verify(repository).saveAndFlush(any(Account.class));
    order.verify(idempotency).saveAndFlush(any(IdempotencyRecord.class));
  }

  @Test
  void neverSavesWhenLimitReached() {
    var repository = mock(AccountRepository.class);
    var idempotency = mock(IdempotencyRepository.class);
    var lock = mock(CustomerAccountLock.class);
    when(repository.countByCustomerKey(TEST_SUBJECT)).thenReturn(5L);
    assertThatThrownBy(
            () ->
                new AccountService(
                        repository, idempotency, lock, mock(OffensiveNicknameValidator.class))
                    .createAccount(
                        new CreateAccountRequest(TEST_CUSTOMER_NAME, null),
                        TEST_REQUEST_KEY,
                        TEST_OWNER))
        .isInstanceOf(BusinessException.class);
    verify(repository, never()).saveAndFlush(any());
    verify(idempotency, never()).saveAndFlush(any());
  }

  @Test
  void permitsFifthAccountAndNormalizesName() {
    var repository = mock(AccountRepository.class);
    var idempotency = mock(IdempotencyRepository.class);
    var lock = mock(CustomerAccountLock.class);
    when(repository.countByCustomerKey("alice smith")).thenReturn(4L);
    when(repository.saveAndFlush(any(Account.class))).thenAnswer(call -> call.getArgument(0));
    var response =
        new AccountService(repository, idempotency, lock, mock(OffensiveNicknameValidator.class))
            .createAccount(
                new CreateAccountRequest(" Alice   Smith ", "Holiday"),
                TEST_REQUEST_KEY,
                TEST_OWNER);
    assertThat(response.customerName()).isEqualTo("Alice Smith");
    assertThat(response.accountNickName()).isEqualTo("Holiday");
    verify(repository).saveAndFlush(any(Account.class));
  }

  @Test
  void getMissingAccountReturns404() {
    var repository = mock(AccountRepository.class);
    var service =
        new AccountService(
            repository,
            mock(IdempotencyRepository.class),
            mock(CustomerAccountLock.class),
            mock(OffensiveNicknameValidator.class));
    assertThatThrownBy(() -> service.getAccount(java.util.UUID.randomUUID(), TEST_OWNER))
        .isInstanceOfSatisfying(
            org.springframework.web.server.ResponseStatusException.class,
            ex -> assertThat(ex.getStatusCode().value()).isEqualTo(404));
  }

  @Test
  void getOtherOwnersAccountIsDenied() {
    var repository = mock(AccountRepository.class);
    var id = java.util.UUID.randomUUID();
    when(repository.findById(id))
        .thenReturn(Optional.of(new Account(TEST_CUSTOMER_NAME, TEST_SUBJECT, null, TEST_OWNER)));
    var service =
        new AccountService(
            repository,
            mock(IdempotencyRepository.class),
            mock(CustomerAccountLock.class),
            mock(OffensiveNicknameValidator.class));
    assertThatThrownBy(() -> service.getAccount(id, "someone-else"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
  }

  @Test
  void corruptedIdempotencyReferenceFailsWithoutCreatingAnotherAccount() {
    var repository = mock(AccountRepository.class);
    var idempotency = mock(IdempotencyRepository.class);
    var lock = mock(CustomerAccountLock.class);
    // Capture a real request fingerprint from a successful creation without duplicating its
    // implementation in the test.
    when(repository.saveAndFlush(any(Account.class))).thenAnswer(call -> call.getArgument(0));
    var service =
        new AccountService(repository, idempotency, lock, mock(OffensiveNicknameValidator.class));
    var request = new CreateAccountRequest(TEST_CUSTOMER_NAME, null);
    service.createAccount(request, TEST_REQUEST_KEY, TEST_OWNER);
    var idempotencyRecord = org.mockito.ArgumentCaptor.forClass(IdempotencyRecord.class);
    verify(idempotency).saveAndFlush(idempotencyRecord.capture());
    var missingId = java.util.UUID.randomUUID();
    when(idempotency.findByOwnerSubjectAndIdempotencyKey(TEST_OWNER, TEST_REQUEST_KEY))
        .thenReturn(
            Optional.of(
                new IdempotencyRecord(
                    TEST_OWNER,
                    TEST_REQUEST_KEY,
                    idempotencyRecord.getValue().getRequestHash(),
                    missingId)));
    clearInvocations(repository);
    clearInvocations(idempotency);
    assertThatThrownBy(() -> service.createAccount(request, TEST_REQUEST_KEY, TEST_OWNER))
        .isInstanceOf(IllegalStateException.class);
    verify(repository, never()).saveAndFlush(any());
    verify(repository, never()).countByCustomerKey(anyString());
  }

  @Test
  void offensiveNicknameDoesNotReachSave() {
    var repository = mock(AccountRepository.class);
    var idempotency = mock(IdempotencyRepository.class);
    var policy = mock(OffensiveNicknameValidator.class);
    doThrow(new BusinessException("Nickname rejected")).when(policy).validate("idiot savings");
    var service =
        new AccountService(repository, idempotency, mock(CustomerAccountLock.class), policy);
    assertThatThrownBy(
            () ->
                service.createAccount(
                    new CreateAccountRequest(TEST_CUSTOMER_NAME, "idiot savings"),
                    TEST_REQUEST_KEY,
                    TEST_OWNER))
        .isInstanceOf(BusinessException.class);
    verify(repository, never()).saveAndFlush(any());
    verify(idempotency, never()).saveAndFlush(any());
  }
}
