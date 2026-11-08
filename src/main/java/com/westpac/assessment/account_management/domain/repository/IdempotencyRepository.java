package com.westpac.assessment.account_management.domain.repository;

import com.westpac.assessment.account_management.domain.model.IdempotencyRecord;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, UUID> {
  Optional<IdempotencyRecord> findByOwnerSubjectAndIdempotencyKey(
      String ownerSubject, String idempotencyKey);
}
