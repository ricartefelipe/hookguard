package br.com.ricarte.hookguard.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
    Optional<Project> findByProjectKey(String projectKey);

    List<Project> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
}
