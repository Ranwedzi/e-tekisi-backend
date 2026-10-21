package com.etekisi.etekisi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The encrypted bytes of a {@link DriverDocument} (IV + AES-GCM ciphertext, bound to the driver). */
@Entity
@Table(name = "driver_document_files", schema = "driver_schema")
@Getter
@Setter
public class DriverDocumentFile {

    @Id
    @Column(name = "document_id")
    private Long documentId;

    @Column(nullable = false, columnDefinition = "bytea")
    @JdbcTypeCode(SqlTypes.VARBINARY)
    private byte[] content;
}
