package com.westpac.assessment.account_management.domain.repository;

import com.westpac.assessment.account_management.domain.model.Account;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, UUID> {
  long countByCustomerKey(String customerKey);
}
