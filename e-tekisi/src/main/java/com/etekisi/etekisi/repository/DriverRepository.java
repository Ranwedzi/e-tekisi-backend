package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.Driver;
import com.etekisi.etekisi.model.DriverApproval;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DriverRepository extends JpaRepository<Driver, Long> {
    Optional<Driver> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByIdAndDeletedAtIsNotNull(Long id);

    /** Operators' review queue, oldest sign-ups first. */
    List<Driver> findTop200ByApprovalStatusOrderByRegisteredAtAsc(DriverApproval status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Driver d where d.id = :id")
    Optional<Driver> findByIdForUpdate(@Param("id") Long id);
}
