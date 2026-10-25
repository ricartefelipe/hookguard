package br.com.ricarte.hookguard.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestDedupeRepository extends JpaRepository<IngestDedupe, IngestDedupe.Key> {
}
