package com.mockinterview.backend.repository;

import com.mockinterview.backend.entity.Token;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TokenRepository extends JpaRepository<Token, Long> {
    /** A single exists query — loading the Token entity instead would also pull its (eager) user,
     *  an extra statement on every authenticated request just to read one boolean. */
    boolean existsByTokenValueAndRevokedTrue(String tokenValue);
}
