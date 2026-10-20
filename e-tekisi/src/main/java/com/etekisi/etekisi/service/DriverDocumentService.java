package com.etekisi.etekisi.service;

import com.etekisi.etekisi.model.Driver;
import com.etekisi.etekisi.model.DriverApproval;
import com.etekisi.etekisi.model.DriverDocument;
import com.etekisi.etekisi.model.DriverDocument.Status;
import com.etekisi.etekisi.model.DriverDocument.Type;
import com.etekisi.etekisi.model.DriverDocumentFile;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.repository.DriverDocumentFileRepository;
import com.etekisi.etekisi.repository.DriverDocumentRepository;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.security.FieldEncryptor;
import com.etekisi.etekisi.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Driver vetting documents.
 *
 * <ul>
 *   <li>Drivers upload PDF, JPEG, PNG or WebP files up to
 *       {@code tekisi.documents.max-bytes} (5 MB). The type is taken from the
 *       file's first bytes, not from what the browser claims.</li>
 *   <li>Files are encrypted with {@code DATA_ENCRYPTION_KEY}, bound to the
 *       driver, before they are stored. Every time staff open one it is
 *       logged (POPIA accountability).</li>
 *   <li>A driver can only be approved once every type in
 *       {@code tekisi.drivers.required-documents} has an accepted,
 *       unexpired document.</li>
 *   <li>Daily: an accepted document past its expiry date takes an approved
 *       driver offline and back to "being checked" until a new one is
 *       accepted; rejected and replaced documents are deleted after 90 days.</li>
 * </ul>
 */
@Service
public class DriverDocumentService {

    private static final Logger log = LoggerFactory.getLogger(DriverDocumentService.class);
    private static final ZoneId SA = ZoneId.of("Africa/Johannesburg");

    public record Upload(Type type, String fileName, byte[] bytes, LocalDate expiresOn) {}
    public record FileContent(String fileName, String contentType, byte[] bytes) {}

    private final DriverDocumentRepository documents;
    private final DriverDocumentFileRepository files;
    private final DriverRepository drivers;
    private final FieldEncryptor encryptor;
    private final NotificationService notifications;
    private final int maxBytes;
    private final List<Type> required;

    public DriverDocumentService(DriverDocumentRepository documents, DriverDocumentFileRepository files,
                                 DriverRepository drivers, FieldEncryptor encryptor, NotificationService notifications,
                                 @Value("${tekisi.documents.max-bytes:5242880}") int maxBytes,
                                 @Value("${tekisi.drivers.required-documents:DRIVERS_LICENCE,PRDP,OPERATING_LICENCE}") List<Type> required) {
        this.documents = documents;
        this.files = files;
        this.drivers = drivers;
        this.encryptor = encryptor;
        this.notifications = notifications;
        this.maxBytes = maxBytes;
        this.required = List.copyOf(required);
    }

    public List<Type> requiredTypes() {
        return required;
    }

    public List<DriverDocument> list(Long driverId) {
        return documents.findByDriverIdOrderByUploadedAtDesc(driverId);
    }

    // ---------------------------------------------------------------- driver --

    @Transactional
    public DriverDocument upload(Long driverId, Upload up) {
        if (up.type() == null) throw ApiException.badRequest("TYPE_REQUIRED", "Choose which document this is.");
        byte[] bytes = up.bytes();
        if (bytes == null || bytes.length == 0) throw ApiException.badRequest("EMPTY_FILE", "That file is empty.");
        if (bytes.length > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                    "Files can be up to " + (maxBytes / (1024 * 1024)) + " MB. Take the photo again at a lower resolution.", null);
        }
        String contentType = sniff(bytes);
        if (contentType == null) {
            throw ApiException.badRequest("UNSUPPORTED_FILE", "Upload a photo (JPEG, PNG, WebP) or a PDF.");
        }
        if (up.expiresOn() != null && up.expiresOn().isBefore(LocalDate.now(SA))) {
            throw ApiException.badRequest("ALREADY_EXPIRED", "That document has already expired. Upload the current one.");
        }
        Driver driver = drivers.findById(driverId).orElseThrow(() -> ApiException.notFound("Driver"));
        if (driver.getDeletedAt() != null) throw ApiException.notFound("Driver");

        // A new upload replaces one of the same type that hasn't been reviewed yet.
        for (DriverDocument old : documents.findByDriverIdAndTypeAndStatus(driverId, up.type(), Status.SUBMITTED)) {
            files.deleteByDocumentIds(List.of(old.getId()));
            documents.delete(old);
        }

        DriverDocument d = new DriverDocument();
        d.setDriverId(driverId);
        d.setType(up.type());
        d.setFileName(cleanName(up.fileName(), contentType));
        d.setContentType(contentType);
        d.setSizeBytes(bytes.length);
        d.setSha256(sha256(bytes));
        d.setExpiresOn(up.expiresOn());
        d = documents.save(d);

        DriverDocumentFile f = new DriverDocumentFile();
        f.setDocumentId(d.getId());
        f.setContent(encryptor.encryptBytes(bytes, context(driverId)));
        files.save(f);
        log.info("documents.uploaded driverId={} id={} type={} bytes={}", driverId, d.getId(), d.getType(), bytes.length);
        return d;
    }

    /** Drivers can withdraw a document until it has been reviewed. */
    @Transactional
    public void withdraw(Long driverId, Long documentId) {
        DriverDocument d = documents.findById(documentId).filter(x -> x.getDriverId().equals(driverId))
                .orElseThrow(() -> ApiException.notFound("Document"));
        if (d.getStatus() != Status.SUBMITTED) {
            throw ApiException.conflict("ALREADY_REVIEWED", "This document has already been reviewed. Upload a new one instead.");
        }
        files.deleteByDocumentIds(List.of(d.getId()));
        documents.delete(d);
    }

    /** The driver's own file, or (for staff) anyone's. Staff access is logged. */
    @Transactional(readOnly = true)
    public FileContent read(Long documentId, Long callerId, Role callerRole) {
        DriverDocument d = documents.findById(documentId).orElseThrow(() -> ApiException.notFound("Document"));
        boolean staff = callerRole.isStaff();
        if (!staff && !(callerRole == Role.DRIVER && d.getDriverId().equals(callerId))) {
            throw ApiException.notFound("Document");
        }
        DriverDocumentFile f = files.findById(documentId).orElseThrow(() -> ApiException.notFound("Document"));
        if (staff) {
            log.info("documents.viewed by={} role={} driverId={} id={} type={}", callerId, callerRole, d.getDriverId(), d.getId(), d.getType());
        }
        return new FileContent(d.getFileName(), d.getContentType(), encryptor.decryptBytes(f.getContent(), context(d.getDriverId())));
    }

    // ----------------------------------------------------------------- staff --

    @Transactional
    public DriverDocument accept(Long documentId, Long operatorId, LocalDate expiresOn) {
        DriverDocument d = documents.findById(documentId).orElseThrow(() -> ApiException.notFound("Document"));
        if (d.getStatus() != Status.SUBMITTED) {
            throw ApiException.conflict("ALREADY_REVIEWED", "This document has already been reviewed.");
        }
        if (expiresOn != null) d.setExpiresOn(expiresOn); // the operator reads it off the document
        if (d.getExpiresOn() != null && d.getExpiresOn().isBefore(LocalDate.now(SA))) {
            throw ApiException.conflict("DOCUMENT_EXPIRED", "The expiry date has passed; reject it and ask for the current one.");
        }
        for (DriverDocument older : documents.findByDriverIdAndTypeAndStatus(d.getDriverId(), d.getType(), Status.ACCEPTED)) {
            older.setStatus(Status.SUPERSEDED);
        }
        d.setStatus(Status.ACCEPTED);
        d.setReviewNote(null);
        d.setReviewedAt(Instant.now());
        d.setReviewedBy(operatorId);
        return d;
    }

    @Transactional
    public DriverDocument reject(Long documentId, Long operatorId, String reason) {
        DriverDocument d = documents.findById(documentId).orElseThrow(() -> ApiException.notFound("Document"));
        if (d.getStatus() != Status.SUBMITTED) {
            throw ApiException.conflict("ALREADY_REVIEWED", "This document has already been reviewed.");
        }
        d.setStatus(Status.REJECTED);
        d.setReviewNote(reason.trim());
        d.setReviewedAt(Instant.now());
        d.setReviewedBy(operatorId);
        notifications.send(d.getDriverId(), Role.DRIVER,
                "Your " + label(d.getType()) + " wasn't accepted: " + reason.trim() + ". Please upload it again.");
        return d;
    }

    /** Required types without an accepted, unexpired document. Empty means the driver can be approved. */
    public List<Type> missingForApproval(Long driverId) {
        LocalDate today = LocalDate.now(SA);
        List<Type> missing = new ArrayList<>();
        for (Type t : required) {
            boolean ok = documents.findByDriverIdAndTypeAndStatus(driverId, t, Status.ACCEPTED).stream()
                    .anyMatch(d -> d.getExpiresOn() == null || !d.getExpiresOn().isBefore(today));
            if (!ok) missing.add(t);
        }
        return missing;
    }

    public void requireComplete(Long driverId) {
        List<Type> missing = missingForApproval(driverId);
        if (!missing.isEmpty()) {
            throw ApiException.conflict("DOCUMENTS_INCOMPLETE",
                    "Accept these documents before approving: " + String.join(", ", missing.stream().map(DriverDocumentService::label).toList()) + ".",
                    Map.of("missing", missing));
        }
    }

    // ------------------------------------------------------------- deletion --

    @Transactional
    public void deleteAllFor(Long driverId) {
        List<Long> ids = list(driverId).stream().map(DriverDocument::getId).toList();
        if (!ids.isEmpty()) files.deleteByDocumentIds(ids);
        documents.deleteByDriver(driverId);
    }

    // ----------------------------------------------------------------- daily --

    /**
     * Expired documents take the driver off the road until a current one is
     * accepted; old rejected/superseded documents are deleted.
     */
    @Scheduled(cron = "${tekisi.documents.check-cron:0 0 4 * * *}", zone = "Africa/Johannesburg")
    @Transactional
    public void dailyCheck() {
        LocalDate today = LocalDate.now(SA);
        for (DriverDocument d : documents.findByStatusAndExpiresOnBefore(Status.ACCEPTED, today)) {
            d.setStatus(Status.EXPIRED);
            if (!required.contains(d.getType())) continue;
            drivers.findById(d.getDriverId()).filter(Driver::isApproved).ifPresent(driver -> {
                driver.setApprovalStatus(DriverApproval.PENDING_APPROVAL);
                driver.setActive(false);
                driver.setApprovalNote("Your " + label(d.getType()) + " expired on " + d.getExpiresOn()
                        + ". Upload the new one to start taking trips again.");
                notifications.send(driver.getId(), Role.DRIVER, driver.getApprovalNote());
                log.info("documents.expired_suspension driverId={} type={} expiredOn={}", driver.getId(), d.getType(), d.getExpiresOn());
            });
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(90));
        List<Long> old = documents.findByStatusInAndUploadedAtBefore(EnumSet.of(Status.REJECTED, Status.SUPERSEDED), cutoff)
                .stream().map(DriverDocument::getId).toList();
        if (!old.isEmpty()) {
            files.deleteByDocumentIds(old);
            documents.deleteAllById(old);
            log.info("documents.retention.purged deleted={}", old.size());
        }
    }

    // --------------------------------------------------------------- helpers --

    public static String label(Type t) {
        return switch (t) {
            case DRIVERS_LICENCE -> "driver's licence";
            case PRDP -> "professional driving permit (PrDP)";
            case OPERATING_LICENCE -> "operating licence";
            case ROADWORTHY -> "roadworthy certificate";
            case VEHICLE_PHOTO -> "vehicle photo";
            case ID_DOCUMENT -> "ID document";
            case OTHER -> "document";
        };
    }

    private static String context(Long driverId) {
        return "driver-document:" + driverId;
    }

    /** By magic number: the browser's Content-Type and the file name are not trusted. */
    static String sniff(byte[] b) {
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return "application/pdf";
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    private static String cleanName(String name, String contentType) {
        String base = name == null ? "" : name.replaceAll(".*[/\\\\]", "").replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (base.isEmpty()) {
            base = "document." + contentType.substring(contentType.indexOf('/') + 1);
        }
        return base.length() > 120 ? base.substring(base.length() - 120) : base;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
