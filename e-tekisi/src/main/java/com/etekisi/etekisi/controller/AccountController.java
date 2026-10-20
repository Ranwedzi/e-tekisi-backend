package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.security.CurrentUser;
import com.etekisi.etekisi.service.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

/** The signed-in user's own account: download their data, or delete the account (POPIA). */
@RestController
@RequestMapping("/api/me")
public class AccountController {

    public record DeleteAccountRequest(@NotBlank String password) {}

    private final AccountService accounts;
    private final CurrentUser currentUser;

    public AccountController(AccountService accounts, CurrentUser currentUser) {
        this.accounts = accounts;
        this.currentUser = currentUser;
    }

    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> export() {
        Map<String, Object> data = accounts.export(currentUser.id(), currentUser.role());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"e-tekisi-my-data-" + LocalDate.now() + ".json\"")
                .body(data);
    }

    /** POST, not DELETE, so the password travels in a body every client sends reliably. */
    @PostMapping("/delete")
    public Map<String, Object> delete(@Valid @RequestBody DeleteAccountRequest body) {
        accounts.delete(currentUser.id(), currentUser.role(), body.password());
        return Map.of("status", "DELETED");
    }
}
