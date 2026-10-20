package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.config.JwtTokenService;
import com.etekisi.etekisi.dto.api.CommuterDto;
import com.etekisi.etekisi.dto.api.DriverDto;
import com.etekisi.etekisi.dto.api.LoginRequest;
import com.etekisi.etekisi.dto.api.RegisterCommuterRequest;
import com.etekisi.etekisi.dto.api.RegisterDriverRequest;
import com.etekisi.etekisi.model.Driver;
import com.etekisi.etekisi.model.DriverApproval;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.model.StaffUser;
import com.etekisi.etekisi.model.User;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.repository.StaffUserRepository;
import com.etekisi.etekisi.repository.UserRepository;
import com.etekisi.etekisi.security.CurrentUser;
import com.etekisi.etekisi.security.LoginThrottle;
import com.etekisi.etekisi.service.AccountSecurityService;
import com.etekisi.etekisi.service.StaffMfaService;
import com.etekisi.etekisi.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class RegistrationController {

    private final UserRepository userRepository;
    private final DriverRepository driverRepository;
    private final StaffUserRepository staffRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtTokenService jwt;
    private final CurrentUser currentUser;
    private final LoginThrottle throttle;
    private final AccountSecurityService accountSecurity;
    private final StaffMfaService staffMfa;
    /** Compared against when the email is unknown, so a miss costs the same as a wrong password. */
    private final String dummyHash;

    public RegistrationController(UserRepository userRepository,
                                  DriverRepository driverRepository,
                                  StaffUserRepository staffRepository,
                                  BCryptPasswordEncoder passwordEncoder,
                                  JwtTokenService jwt,
                                  CurrentUser currentUser,
                                  LoginThrottle throttle,
                                  AccountSecurityService accountSecurity,
                                  StaffMfaService staffMfa) {
        this.userRepository = userRepository;
        this.driverRepository = driverRepository;
        this.staffRepository = staffRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.currentUser = currentUser;
        this.throttle = throttle;
        this.accountSecurity = accountSecurity;
        this.staffMfa = staffMfa;
        this.dummyHash = passwordEncoder.encode("timing-equaliser-not-a-real-password");
    }

    @PostMapping("/register/commuter")
    @Transactional
    public Map<String, Object> registerUser(@Valid @RequestBody RegisterCommuterRequest req, HttpServletRequest http) {
        throttle.checkIp(http.getRemoteAddr());
        if (userRepository.existsByEmailIgnoreCase(req.email())) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists.");
        }
        accountSecurity.checkPassword(req.password(), req.email(), req.fullName());
        User user = new User();
        user.setFullName(req.fullName());
        user.setEmail(req.email());
        user.setPhoneNumber(req.phoneNumber());
        user.setPassword(passwordEncoder.encode(req.password()));
        accountSecurity.recordConsent(user, req.acceptTerms(), req.termsVersion());
        User saved = userRepository.save(user);
        return Map.of("status", "REGISTERED", "userId", saved.getId(), "role", Role.COMMUTER.name(),
                "verification", accountSecurity.sendInitialVerification(saved));
    }

    @PostMapping("/register/driver")
    @Transactional
    public Map<String, Object> registerDriver(@Valid @RequestBody RegisterDriverRequest req, HttpServletRequest http) {
        throttle.checkIp(http.getRemoteAddr());
        if (driverRepository.existsByEmailIgnoreCase(req.email())) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists.");
        }
        accountSecurity.checkPassword(req.password(), req.email(), req.fullName());
        Driver driver = new Driver();
        driver.setFullName(req.fullName());
        driver.setEmail(req.email());
        driver.setPhoneNumber(req.phoneNumber());
        driver.setTaxiPlate(req.taxiPlate());
        driver.setLicenseNumber(req.licenseNumber());
        driver.setPassword(passwordEncoder.encode(req.password()));
        // New drivers wait for an operator to check their details before they can take trips.
        driver.setApprovalStatus(DriverApproval.PENDING_APPROVAL);
        accountSecurity.recordConsent(driver, req.acceptTerms(), req.termsVersion());
        Driver saved = driverRepository.save(driver);
        return Map.of("status", "REGISTERED", "userId", saved.getId(), "role", Role.DRIVER.name(),
                "approvalStatus", saved.getApprovalStatus().name(),
                "verification", accountSecurity.sendInitialVerification(saved));
    }

    /**
     * On success, mints an HS256 JWT carrying {@code sub=userId} and
     * {@code role}. Failure cases return HTTP 401 with a uniform body so
     * callers can't fingerprint which side (email vs. password) was wrong.
     * Repeated failures lock the account for a while (429), see LoginThrottle.
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest creds, HttpServletRequest http) {
        String email = creds.email().trim();
        String password = creds.password();
        String accountKey = LoginThrottle.accountKey(creds.role().name(), email);
        throttle.checkIp(http.getRemoteAddr());
        throttle.checkAccount(accountKey);

        switch (creds.role()) {
            case COMMUTER -> {
                User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
                if (matches(password, user != null ? user.getPassword() : null)) {
                    throttle.recordSuccess(accountKey);
                    return ok(user.getId(), Role.COMMUTER);
                }
            }
            case DRIVER -> {
                Driver driver = driverRepository.findByEmailIgnoreCase(email).orElse(null);
                if (matches(password, driver != null ? driver.getPassword() : null)) {
                    throttle.recordSuccess(accountKey);
                    return ok(driver.getId(), Role.DRIVER);
                }
            }
            case OPERATOR, ADMIN -> {
                StaffUser staff = staffRepository.findByEmailIgnoreCase(email).orElse(null);
                if (matches(password, staff != null ? staff.getPassword() : null)) {
                    // With two-step sign-in, the password only earns a challenge for the code
                    // (and doesn't reset the lockout count: StaffMfaService does that).
                    Map<String, Object> challenge = staffMfa.challengeFor(staff);
                    if (challenge != null) return ResponseEntity.ok(challenge);
                    throttle.recordSuccess(accountKey);
                    return ok(staff.getId(), staff.getRole());
                }
            }
        }
        // No user found or wrong password — same response either way.
        throttle.recordFailure(accountKey);
        Map<String, Object> failed = new HashMap<>();
        failed.put("status", "FAIL");
        failed.put("message", "Invalid email or password");
        return ResponseEntity.status(401).body(failed);
    }

    private boolean matches(String raw, String hash) {
        if (hash == null) {
            passwordEncoder.matches(raw, dummyHash);
            return false;
        }
        return passwordEncoder.matches(raw, hash);
    }

    private ResponseEntity<Map<String, Object>> ok(Long userId, Role role) {
        return ResponseEntity.ok(jwt.loginBody(userId, role.name()));
    }

    @GetMapping("/commuter/{id}")
    public CommuterDto getCommuter(@PathVariable Long id) {
        currentUser.requireSelf(id, Role.COMMUTER);
        return userRepository.findById(id).map(CommuterDto::from)
                .orElseThrow(() -> ApiException.notFound("Commuter"));
    }

    @GetMapping("/driver/{id}")
    public DriverDto getDriver(@PathVariable Long id) {
        currentUser.requireSelf(id, Role.DRIVER);
        return driverRepository.findById(id).map(DriverDto::from)
                .orElseThrow(() -> ApiException.notFound("Driver"));
    }

    @PutMapping("/driver/{id}/status")
    @Transactional
    public DriverDto toggleStatus(@PathVariable Long id, @RequestParam boolean active) {
        currentUser.requireSelf(id, Role.DRIVER);
        Driver driver = driverRepository.findById(id).orElseThrow(() -> ApiException.notFound("Driver"));
        if (active) AccountSecurityService.requireVerified(driver);
        if (active && !driver.isApproved()) {
            throw ApiException.conflict("DRIVER_NOT_APPROVED",
                    driver.getApprovalStatus() == DriverApproval.REJECTED
                            ? "Your driver account was not approved" + (driver.getApprovalNote() != null ? ": " + driver.getApprovalNote() : ".")
                            : "Your driver account is still being checked. You can go online once it's approved.");
        }
        driver.setActive(active);
        return DriverDto.from(driverRepository.save(driver));
    }
}
