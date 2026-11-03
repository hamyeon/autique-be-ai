package com.vintic.backend.ai.naming;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface KoreanProductNameRepository extends JpaRepository<KoreanProductName, Long> {

    Optional<KoreanProductName> findByLookupKey(String lookupKey);
}
