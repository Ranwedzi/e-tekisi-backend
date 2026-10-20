package com.etekisi.etekisi.dto.api;

import com.etekisi.etekisi.model.DriverDocument;
import com.etekisi.etekisi.service.DriverDocumentService;

import java.util.List;

/** A driver document without its file. */
public record DocumentDto(Long id, String type, String label, String status, String fileName, String contentType,
                          int sizeBytes, String expiresOn, String reviewNote, String uploadedAt, String reviewedAt) {

    public static DocumentDto from(DriverDocument d) {
        return new DocumentDto(d.getId(), d.getType().name(), DriverDocumentService.label(d.getType()), d.getStatus().name(),
                d.getFileName(), d.getContentType(), d.getSizeBytes(),
                d.getExpiresOn() == null ? null : d.getExpiresOn().toString(), d.getReviewNote(),
                d.getUploadedAt().toString(), d.getReviewedAt() == null ? null : d.getReviewedAt().toString());
    }

    /** What the driver (or the operator reviewing them) sees: required types, what's still missing, and the list. */
    public record Summary(List<String> required, List<String> missing, List<DocumentDto> items) {}
}
