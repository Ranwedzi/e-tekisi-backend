package com.etekisi.etekisi.integration;

import com.etekisi.etekisi.model.Driver;
import com.etekisi.etekisi.model.DriverApproval;
import com.etekisi.etekisi.model.DriverDocument;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.repository.DriverDocumentFileRepository;
import com.etekisi.etekisi.repository.DriverDocumentRepository;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.service.DriverDocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.Arrays;

import static com.etekisi.etekisi.integration.TestAuth.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Drivers upload vetting documents; operators review them before approving. */
@SpringBootTest
class DriverDocumentTest {

    private static final long OPERATOR = 9500L;
    /** A minimal PDF header is all the type check looks at. */
    private static final byte[] PDF = "%PDF-1.4\n% e-Tekisi test licence\n%%EOF\n".getBytes();
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @Autowired private WebApplicationContext context;
    @Autowired private Fixtures fixtures;
    @Autowired private DriverRepository drivers;
    @Autowired private DriverDocumentRepository documents;
    @Autowired private DriverDocumentFileRepository files;
    @Autowired private DriverDocumentService service;

    private Driver pendingDriver() {
        Driver d = fixtures.driver();
        d.setApprovalStatus(DriverApproval.PENDING_APPROVAL);
        d.setActive(false);
        return drivers.save(d);
    }

    private long upload(MockMvc mvc, long driverId, String type, byte[] bytes, String expiresOn) throws Exception {
        var req = multipart("/api/me/documents").file(new MockMultipartFile("file", "scan.pdf", "application/pdf", bytes))
                .param("type", type).with(as(Role.DRIVER, driverId));
        if (expiresOn != null) req.param("expiresOn", expiresOn);
        String body = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void uploadReviewAndApprove() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        Driver d = pendingDriver();
        String nextYear = LocalDate.now().plusYears(1).toString();

        mvc.perform(get("/api/me/documents").with(as(Role.DRIVER, d.getId())))
                .andExpect(jsonPath("$.missing.length()").value(3));

        long licence = upload(mvc, d.getId(), "DRIVERS_LICENCE", PDF, nextYear);
        long prdp = upload(mvc, d.getId(), "PRDP", PNG, nextYear);
        long permit = upload(mvc, d.getId(), "OPERATING_LICENCE", PDF, null);

        // Stored encrypted: the bytes at rest are not the file.
        byte[] atRest = files.findById(licence).orElseThrow().getContent();
        assertThat(Arrays.equals(atRest, PDF)).isFalse();
        assertThat(new String(atRest, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("%PDF");

        // The operator sees the list and opens the file.
        mvc.perform(get("/api/ops/drivers/" + d.getId() + "/documents").with(as(Role.OPERATOR, OPERATOR)))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[?(@.id == " + prdp + ")].contentType").value("image/png"));
        mvc.perform(get("/api/ops/documents/" + licence + "/file").with(as(Role.OPERATOR, OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(PDF));

        mvc.perform(put("/api/ops/documents/" + licence + "/accept").with(as(Role.OPERATOR, OPERATOR)))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(put("/api/ops/documents/" + prdp + "/accept").with(as(Role.OPERATOR, OPERATOR)))
                .andExpect(status().isOk());
        mvc.perform(put("/api/ops/documents/" + permit + "/reject").with(as(Role.OPERATOR, OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"The photo is blurry\"}"))
                .andExpect(jsonPath("$.status").value("REJECTED"));
        mvc.perform(put("/api/ops/drivers/" + d.getId() + "/approve").with(as(Role.OPERATOR, OPERATOR)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.missing[0]").value("OPERATING_LICENCE"));

        long permit2 = upload(mvc, d.getId(), "OPERATING_LICENCE", PDF, null);
        mvc.perform(put("/api/ops/documents/" + permit2 + "/accept").with(as(Role.OPERATOR, OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expiresOn\":\"" + nextYear + "\"}"))
                .andExpect(jsonPath("$.expiresOn").value(nextYear));
        mvc.perform(put("/api/ops/drivers/" + d.getId() + "/approve").with(as(Role.OPERATOR, OPERATOR)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/me/documents").with(as(Role.DRIVER, d.getId())))
                .andExpect(jsonPath("$.missing.length()").value(0))
                .andExpect(jsonPath("$.items[?(@.id == " + permit + ")].reviewNote").value("The photo is blurry"));
    }

    @Test
    void onlyRealDocumentsOfReasonableSizeAreAccepted() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        Driver d = pendingDriver();
        mvc.perform(multipart("/api/me/documents")
                        .file(new MockMultipartFile("file", "licence.pdf", "application/pdf", "<script>alert(1)</script>".getBytes()))
                        .param("type", "DRIVERS_LICENCE").with(as(Role.DRIVER, d.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE"));
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        mvc.perform(multipart("/api/me/documents").file(new MockMultipartFile("file", "big.pdf", "application/pdf", big))
                        .param("type", "PRDP").with(as(Role.DRIVER, d.getId())))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(multipart("/api/me/documents").file(new MockMultipartFile("file", "old.pdf", "application/pdf", PDF))
                        .param("type", "PRDP").param("expiresOn", "2020-01-01").with(as(Role.DRIVER, d.getId())))
                .andExpect(jsonPath("$.code").value("ALREADY_EXPIRED"));
    }

    @Test
    void driversOnlySeeTheirOwnDocuments() throws Exception {
        MockMvc mvc = TestAuth.mvc(context);
        Driver owner = pendingDriver();
        Driver other = pendingDriver();
        long id = upload(mvc, owner.getId(), "DRIVERS_LICENCE", PDF, null);
        mvc.perform(get("/api/me/documents/" + id + "/file").with(as(Role.DRIVER, owner.getId())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/me/documents/" + id + "/file").with(as(Role.DRIVER, other.getId())))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/me/documents/" + id).with(as(Role.DRIVER, other.getId())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/ops/documents/" + id + "/file").with(as(Role.DRIVER, owner.getId())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/ops/drivers/" + owner.getId() + "/documents").with(as(Role.COMMUTER, 1L)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anExpiredPermitTakesTheDriverOffTheRoad() {
        Driver d = fixtures.driver(); // approved and online
        DriverDocument permit = new DriverDocument();
        permit.setDriverId(d.getId());
        permit.setType(DriverDocument.Type.PRDP);
        permit.setStatus(DriverDocument.Status.ACCEPTED);
        permit.setFileName("prdp.pdf");
        permit.setContentType("application/pdf");
        permit.setSizeBytes(1);
        permit.setSha256("0".repeat(64));
        permit.setExpiresOn(LocalDate.now().minusDays(1));
        documents.save(permit);

        service.dailyCheck();

        Driver after = drivers.findById(d.getId()).orElseThrow();
        assertThat(after.getApprovalStatus()).isEqualTo(DriverApproval.PENDING_APPROVAL);
        assertThat(after.isActive()).isFalse();
        assertThat(after.getApprovalNote()).contains("professional driving permit (PrDP) expired");
        assertThat(documents.findById(permit.getId()).orElseThrow().getStatus()).isEqualTo(DriverDocument.Status.EXPIRED);
    }
}
