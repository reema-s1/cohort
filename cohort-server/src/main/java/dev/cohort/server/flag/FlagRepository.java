package dev.cohort.server.flag;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface FlagRepository extends JpaRepository<FlagEntity, String> {

    List<FlagEntity> findByArchivedFalseOrderByKey();

    List<FlagEntity> findAllByOrderByKey();

    List<FlagEntity> findByLayerAndArchivedFalse(String layer);

    /** SELECT ... FOR UPDATE, so the version check and the write happen atomically. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from FlagEntity f where f.key = :key")
    Optional<FlagEntity> findForUpdate(String key);
}
