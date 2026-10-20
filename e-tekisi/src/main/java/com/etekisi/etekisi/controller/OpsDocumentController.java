package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.dto.api.DocumentDto;
import com.etekisi.etekisi.security.CurrentUser;
import com.etekisi.etekisi.service.DriverDocumentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** Operators review driver documents (OPERATOR/ADMIN only, via SecurityConfig's /api/ops/** rule). */
@RestController
@RequestMapping("/api/ops")
public class OpsDocumentController {

    public record AcceptRequest(LocalDate expiresOn) {}
    public record RejectRequest(@NotBlank @Size(max = 300) String reason) {}

    private final DriverDocumentService documents;
    private final CurrentUser currentUser;

    public OpsDocumentController(DriverDocumentService documents, CurrentUser currentUser) {
        this.documents = documents;
        this.currentUser = currentUser;
    }

    @GetMapping("/drivers/{driverId}/documents")
    public DocumentDto.Summary forDriver(@PathVariable Long driverId) {
        return DriverDocumentController.summary(documents, driverId);
    }

    /** Each view is logged with the operator's id. */
    @GetMapping("/documents/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable Long id) {
        return DriverDocumentController.fileResponse(documents.read(id, currentUser.id(), currentUser.role()));
    }

    /** Optional {@code expiresOn}: the expiry date as printed on the document, if the driver's entry was wrong. */
    @PutMapping("/documents/{id}/accept")
    public DocumentDto accept(@PathVariable Long id, @RequestBody(required = false) AcceptRequest body) {
        return DocumentDto.from(documents.accept(id, currentUser.id(), body == null ? null : body.expiresOn()));
    }

    @PutMapping("/documents/{id}/reject")
    public DocumentDto reject(@PathVariable Long id, @Valid @RequestBody RejectRequest body) {
        return DocumentDto.from(documents.reject(id, currentUser.id(), body.reason()));
    }
}
