package com.westpac.assessment.account_management.domain.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(
    name = "account_idempotency",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_idempotency_owner_key",
            columnNames = {"owner_subject", "idempotency_key"}))
public class IdempotencyRecord {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "owner_subject", nullable = false, length = 200)
  private String ownerSubject;

  @Column(name = "idempotency_key", nullable = false, length = 128)
  private String idempotencyKey;

  @Column(name = "request_hash", nullable = false, length = 64)
  private String requestHash;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  protected IdempotencyRecord() {}

  public IdempotencyRecord(String owner, String key, String hash, UUID accountId) {
    this.ownerSubject = owner;
    this.idempotencyKey = key;
    this.requestHash = hash;
    this.accountId = accountId;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public UUID getAccountId() {
    return accountId;
  }
}
