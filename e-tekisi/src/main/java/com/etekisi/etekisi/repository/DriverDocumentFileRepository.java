package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.DriverDocumentFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

public interface DriverDocumentFileRepository extends JpaRepository<DriverDocumentFile, Long> {

    /** JPQL deletes don't cascade, so files go first. */
    @Modifying
    @Query("delete from DriverDocumentFile f where f.documentId in :ids")
    int deleteByDocumentIds(@Param("ids") Collection<Long> ids);
}
