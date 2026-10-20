package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.dto.api.DocumentDto;
import com.etekisi.etekisi.model.DriverDocument;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.security.CurrentUser;
import com.etekisi.etekisi.service.DriverDocumentService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/** A driver's own vetting documents: list, upload, withdraw, view. */
@RestController
@RequestMapping("/api/me/documents")
public class DriverDocumentController {

    private final DriverDocumentService documents;
    private final CurrentUser currentUser;

    public DriverDocumentController(DriverDocumentService documents, CurrentUser currentUser) {
        this.documents = documents;
        this.currentUser = currentUser;
    }

    @GetMapping
    public DocumentDto.Summary list() {
        return summary(documents, currentUser.requireRole(Role.DRIVER));
    }

    /** multipart/form-data: {@code type}, {@code file}, optional {@code expiresOn} (YYYY-MM-DD). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocumentDto upload(@RequestParam DriverDocument.Type type,
                              @RequestParam("file") MultipartFile file,
                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiresOn)
            throws IOException {
        Long driverId = currentUser.requireRole(Role.DRIVER);
        return DocumentDto.from(documents.upload(driverId,
                new DriverDocumentService.Upload(type, file.getOriginalFilename(), file.getBytes(), expiresOn)));
    }

    @DeleteMapping("/{id}")
    public void withdraw(@PathVariable Long id) {
        documents.withdraw(currentUser.requireRole(Role.DRIVER), id);
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable Long id) {
        return fileResponse(documents.read(id, currentUser.id(), currentUser.role()));
    }

    static DocumentDto.Summary summary(DriverDocumentService documents, Long driverId) {
        return new DocumentDto.Summary(
                documents.requiredTypes().stream().map(Enum::name).toList(),
                documents.missingForApproval(driverId).stream().map(Enum::name).toList(),
                documents.list(driverId).stream().map(DocumentDto::from).toList());
    }

    /** Never cached; the browser must not guess another type (nosniff), and scripts can't run (sandbox). */
    static ResponseEntity<byte[]> fileResponse(DriverDocumentService.FileContent f) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(f.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self'; style-src 'unsafe-inline'")
                .cacheControl(CacheControl.noStore())
                .body(f.bytes());
    }
}
