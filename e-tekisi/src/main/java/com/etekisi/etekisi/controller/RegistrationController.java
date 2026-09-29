package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.config.JwtTokenService;
import com.etekisi.etekisi.dto.api.CommuterDto;
import com.etekisi.etekisi.dto.api.DriverDto;
import com.etekisi.etekisi.model.User;
import com.etekisi.etekisi.model.Driver;
import com.etekisi.etekisi.repository.UserRepository;
import com.etekisi.etekisi.repository.DriverRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class RegistrationController {

    @Autowired private UserRepository userRepository;
    @Autowired private DriverRepository driverRepository;
    @Autowired private BCryptPasswordEncoder passwordEncoder;
    @Autowired private JwtTokenService jwt;

    @PostMapping("/register/commuter")
    public Map<String, Object> registerUser(@Valid @RequestBody User user) {
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        User saved = userRepository.save(user);
        return Map.of(
                "status", "REGISTERED",
                "userId", saved.getId(),
                "role", "COMMUTER"
        );
    }

    @PostMapping("/register/driver")
    public Map<String, Object> registerDriver(@Valid @RequestBody Driver driver) {
        driver.setPassword(passwordEncoder.encode(driver.getPassword()));
        Driver saved = driverRepository.save(driver);
        return Map.of(
                "status", "REGISTERED",
                "userId", saved.getId(),
                "role", "DRIVER"
        );
    }

    @PutMapping("/driver/{id}/complete-trip")
    public DriverDto completeTrip(@PathVariable Long id, @RequestParam Double amount) {
        return driverRepository.findById(id).map(driver -> {
            driver.setBalance(driver.getBalance() + amount);
            driver.setTripsCompleted(driver.getTripsCompleted() + 1);
            return DriverDto.from(driverRepository.save(driver));
        }).orElse(null);
    }

    /**
     * Bounded login request DTO — replaces {@code Map<String,String>} so the
     * payload is validated and self-documenting.
     */
    public static class LoginRequest {
        @NotBlank private String email;
        @NotBlank private String password;
        @NotBlank private String role; // COMMUTER | DRIVER | OPERATOR | ADMIN
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
    }

    /**
     * On success, mints an HS256 JWT carrying {@code sub=userId} and
     * {@code role}. Failure cases return HTTP 401 with a uniform body so
     * callers can't fingerprint which side (email vs. password) was wrong.
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest creds) {
        String email = creds.getEmail();
        String password = creds.getPassword();
        String role = creds.getRole();

        if ("COMMUTER".equals(role)) {
            User user = userRepository.findByEmail(email).orElse(null);
            if (user != null && passwordEncoder.matches(password, user.getPassword())) {
                return ok(user.getId(), "COMMUTER");
            }
        } else if ("DRIVER".equals(role)) {
            Driver driver = driverRepository.findByEmail(email).orElse(null);
            if (driver != null && passwordEncoder.matches(password, driver.getPassword())) {
                return ok(driver.getId(), "DRIVER");
            }
        }
        // No role match, no user found, or wrong password — same response.
        Map<String, Object> failed = new HashMap<>();
        failed.put("status", "FAIL");
        failed.put("message", "Invalid email or password");
        return ResponseEntity.status(401).body(failed);
    }

    private ResponseEntity<Map<String, Object>> ok(Long userId, String role) {
        String token = jwt.mint(userId, role);
        Map<String, Object> body = new HashMap<>();
        body.put("status", "SUCCESS");
        body.put("userId", userId);
        body.put("role", role);
        body.put("token", token);
        body.put("tokenType", "Bearer");
        body.put("expiresInSeconds", jwt.expirySeconds());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/commuter/{id}")
    public CommuterDto getCommuter(@PathVariable Long id) {
        return userRepository.findById(id).map(CommuterDto::from).orElse(null);
    }

    @GetMapping("/driver/{id}")
    public DriverDto getDriver(@PathVariable Long id) {
        return driverRepository.findById(id).map(DriverDto::from).orElse(null);
    }

    @PutMapping("/driver/{id}/status")
    public DriverDto toggleStatus(@PathVariable Long id, @RequestParam boolean active) {
        Driver driver = driverRepository.findById(id).orElseThrow();
        driver.setActive(active);
        return DriverDto.from(driverRepository.save(driver));
    }
}
