package com.westpac.assessment.account_management.domain.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Database-wide lock, including when the customer has no accounts yet. */
@Component
public class CustomerAccountLock {
  private final JdbcTemplate jdbc;

  public CustomerAccountLock(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void acquire(String customerKey) {
    try {
      long key =
          ByteBuffer.wrap(
                  MessageDigest.getInstance("SHA-256")
                      .digest(customerKey.getBytes(StandardCharsets.UTF_8)))
              .getLong();
      // Released automatically at transaction commit/rollback. Hash collisions only serialize
      // unrelated customers.
      jdbc.queryForList("select pg_advisory_xact_lock(?)", key);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable", ex);
    }
  }
}
