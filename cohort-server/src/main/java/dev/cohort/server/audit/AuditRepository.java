package dev.cohort.server.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditRepository extends JpaRepository<AuditEntry, Long> {

    List<AuditEntry> findByFlagKeyOrderByIdDesc(String flagKey);
}
