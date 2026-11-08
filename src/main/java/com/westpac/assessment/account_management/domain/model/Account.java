package com.westpac.assessment.account_management.domain.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(
    name = "savings_accounts",
    indexes = @Index(name = "idx_accounts_customer_key", columnList = "customer_key"))
public class Account {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "account_number", nullable = false, unique = true, length = 36)
  private String accountNumber;

  @Column(name = "customer_name", nullable = false, length = 200)
  private String customerName;

  @Column(name = "customer_key", nullable = false, length = 200)
  private String customerKey;

  @Column(name = "account_nick_name", length = 30)
  private String accountNickName;

  @Column(name = "owner_subject", nullable = false, length = 200)
  private String ownerSubject;

  protected Account() {}

  public Account(
      String customerName, String customerKey, String accountNickName, String ownerSubject) {
    this.accountNumber = UUID.randomUUID().toString();
    this.customerName = customerName;
    this.customerKey = customerKey;
    this.accountNickName = accountNickName;
    this.ownerSubject = ownerSubject;
  }

  public String getOwnerSubject() {
    return ownerSubject;
  }

  public UUID getId() {
    return id;
  }

  public String getAccountNumber() {
    return accountNumber;
  }

  public String getCustomerName() {
    return customerName;
  }

  public String getCustomerKey() {
    return customerKey;
  }

  public String getAccountNickName() {
    return accountNickName;
  }
}
