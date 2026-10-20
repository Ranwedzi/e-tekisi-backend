package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.DriverDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface DriverDocumentRepository extends JpaRepository<DriverDocument, Long> {

    List<DriverDocument> findByDriverIdOrderByUploadedAtDesc(Long driverId);

    List<DriverDocument> findByDriverIdAndTypeAndStatus(Long driverId, DriverDocument.Type type, DriverDocument.Status status);

    /** Accepted documents whose expiry date has passed (daily job). */
    List<DriverDocument> findByStatusAndExpiresOnBefore(DriverDocument.Status status, LocalDate day);

    /** Retention: rejected and superseded documents are only kept for a while. */
    List<DriverDocument> findByStatusInAndUploadedAtBefore(Collection<DriverDocument.Status> statuses, Instant cutoff);

    @Modifying
    @Query("delete from DriverDocument d where d.driverId = :driverId")
    int deleteByDriver(@Param("driverId") Long driverId);
}
