package com.etekisi.etekisi.service;

import com.etekisi.etekisi.model.*;
import com.etekisi.etekisi.model.AccountToken.Channel;
import com.etekisi.etekisi.model.AccountToken.Purpose;
import com.etekisi.etekisi.notify.DeliveryException;
import com.etekisi.etekisi.notify.EmailSender;
import com.etekisi.etekisi.notify.PhoneNumbers;
import com.etekisi.etekisi.notify.SmsSender;
import com.etekisi.etekisi.repository.AccountTokenRepository;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.repository.UserRepository;
import com.etekisi.etekisi.security.PasswordPolicy;
import com.etekisi.etekisi.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Verification of email / phone, password reset and change, and consent to
 * the current terms. Shared by commuters and drivers through
 * {@link AccountHolder}.
 *
 * <ul>
 *   <li>Email: a link with a random 256-bit token, valid 24 h (reset: 1 h).</li>
 *   <li>SMS: a 6-digit code, valid 10 min, 5 tries.</li>
 *   <li>Only SHA-256 hashes of either are stored; using one voids the others
 *       of the same kind.</li>
 *   <li>Sends are limited to one a minute and five an hour per account and
 *       purpose.</li>
 *   <li>Resetting or changing the password bumps
 *       {@code credentialsChangedAt}, which signs out every existing session
 *       (see {@code AccountStateTokenValidator}).</li>
 * </ul>
 */
@Service
public class AccountSecurityService {

    private static final Logger log = LoggerFactory.getLogger(AccountSecurityService.class);
    private static final Duration EMAIL_VERIFY_TTL = Duration.ofHours(24);
    private static final Duration RESET_LINK_TTL = Duration.ofHours(1);
    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final int MAX_CODE_ATTEMPTS = 5;
    private static final Duration RESEND_GAP = Duration.ofSeconds(60);
    private static final int MAX_SENDS_PER_HOUR = 5;

    private final SecureRandom random = new SecureRandom();
    private final UserRepository users;
    private final DriverRepository drivers;
    private final AccountTokenRepository tokens;
    private final EmailSender email;
    private final SmsSender sms;
    private final BCryptPasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final String publicUrl;
    private final String termsVersion;

    public AccountSecurityService(UserRepository users, DriverRepository drivers, AccountTokenRepository tokens,
                                  EmailSender email, SmsSender sms, BCryptPasswordEncoder encoder, PasswordPolicy policy,
                                  @Value("${tekisi.public-url:http://localhost:3000}") String publicUrl,
                                  @Value("${tekisi.legal.version}") String termsVersion) {
        this.users = users;
        this.drivers = drivers;
        this.tokens = tokens;
        this.email = email;
        this.sms = sms;
        this.encoder = encoder;
        this.policy = policy;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
        this.termsVersion = termsVersion;
    }

    public String termsVersion() {
        return termsVersion;
    }

    public boolean smsAvailable() {
        return sms.available();
    }

    // ------------------------------------------------------------- accounts --

    public AccountHolder find(Long id, Role role) {
        Optional<? extends AccountHolder> a = switch (role) {
            case COMMUTER -> users.findById(id);
            case DRIVER -> drivers.findById(id);
            default -> throw ApiException.forbidden("NOT_SUPPORTED", "Staff accounts are managed by an administrator.");
        };
        return a.filter(x -> x.getDeletedAt() == null).orElseThrow(() -> ApiException.notFound("Account"));
    }

    private Optional<? extends AccountHolder> findByEmail(String emailAddress, Role role) {
        String e = emailAddress == null ? "" : emailAddress.trim();
        return switch (role) {
            case COMMUTER -> users.findByEmailIgnoreCase(e);
            case DRIVER -> drivers.findByEmailIgnoreCase(e);
            default -> Optional.empty();
        };
    }

    private void save(AccountHolder a) {
        if (a instanceof User u) users.save(u);
        else if (a instanceof Driver d) drivers.save(d);
    }

    private static Role roleOf(AccountHolder a) {
        return a instanceof Driver ? Role.DRIVER : Role.COMMUTER;
    }

    // -------------------------------------------------------------- consent --

    /** Sign-up and "accept the new terms": must be the version currently published. */
    public void recordConsent(AccountHolder a, Boolean accepted, String version) {
        if (!Boolean.TRUE.equals(accepted)) {
            throw ApiException.badRequest("TERMS_NOT_ACCEPTED", "Please accept the terms of use and privacy policy to continue.");
        }
        if (!termsVersion.equals(version)) {
            throw ApiException.conflict("TERMS_OUTDATED", "The terms have been updated. Please read the current version.",
                    Map.of("currentVersion", termsVersion));
        }
        a.setTermsVersion(termsVersion);
        a.setTermsAcceptedAt(Instant.now());
    }

    @Transactional
    public void acceptTerms(Long id, Role role, String version) {
        AccountHolder a = find(id, role);
        recordConsent(a, true, version);
        save(a);
    }

    public void checkPassword(String password, String emailAddress, String fullName) {
        policy.check(password, emailAddress, fullName);
    }

    // --------------------------------------------------------- verification --

    /** After sign-up: an email link always, an SMS code too when SMS is set up. Delivery problems don't fail sign-up. */
    public Map<String, String> sendInitialVerification(AccountHolder a) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("email", trySend(() -> sendEmailVerification(a)));
        out.put("phone", sms.available() ? trySend(() -> sendPhoneCode(a, Purpose.VERIFY_PHONE)) : "UNAVAILABLE");
        return out;
    }

    private static String trySend(Runnable send) {
        try {
            send.run();
            return "SENT";
        } catch (DeliveryException | ApiException e) { // e.g. a landline: sign-up still succeeds
            log.error("account.verification.delivery_failed error={}", e.getMessage());
            return "FAILED";
        }
    }

    @Transactional
    public void resendEmailVerification(Long id, Role role) {
        AccountHolder a = find(id, role);
        if (a.getEmailVerifiedAt() != null) {
            throw ApiException.conflict("ALREADY_VERIFIED", "Your email address is already verified.");
        }
        limitSends(a, Purpose.VERIFY_EMAIL, true);
        deliver(() -> sendEmailVerification(a));
    }

    @Transactional
    public void sendPhoneVerification(Long id, Role role) {
        AccountHolder a = find(id, role);
        requireSms();
        if (a.getPhoneVerifiedAt() != null) {
            throw ApiException.conflict("ALREADY_VERIFIED", "Your phone number is already verified.");
        }
        limitSends(a, Purpose.VERIFY_PHONE, true);
        deliver(() -> sendPhoneCode(a, Purpose.VERIFY_PHONE));
    }

    /** From the link in the email; works without being signed in. */
    @Transactional
    public Role verifyEmail(String token) {
        AccountToken t = tokens.findFirstBySecretHashAndChannel(sha256(token == null ? "" : token), Channel.EMAIL)
                .filter(x -> x.getPurpose() == Purpose.VERIFY_EMAIL)
                .orElseThrow(() -> ApiException.badRequest("INVALID_LINK", "This link isn't valid. Ask for a new one from your profile."));
        if (t.getUsedAt() != null || t.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.badRequest("LINK_EXPIRED", "This link has expired or was already used. Ask for a new one from your profile.");
        }
        AccountHolder a = find(t.getOwnerId(), t.getOwnerRole());
        a.setEmailVerifiedAt(Instant.now());
        save(a);
        tokens.invalidate(a.getId(), t.getOwnerRole(), Purpose.VERIFY_EMAIL, Instant.now());
        log.info("account.email_verified role={} id={}", t.getOwnerRole(), a.getId());
        return t.getOwnerRole();
    }

    @Transactional(noRollbackFor = ApiException.class) // keep the attempt count when the code is wrong
    public void confirmPhone(Long id, Role role, String code) {
        AccountHolder a = find(id, role);
        checkCode(a, role, Purpose.VERIFY_PHONE, code);
        a.setPhoneVerifiedAt(Instant.now());
        save(a);
        log.info("account.phone_verified role={} id={}", role, id);
    }

    /** 403 ACCOUNT_NOT_VERIFIED unless the email or phone number has been verified. */
    public static void requireVerified(AccountHolder a) {
        if (!a.isVerified()) {
            throw ApiException.forbidden("ACCOUNT_NOT_VERIFIED",
                    "Verify your email address or phone number first. Check your inbox, or open your profile to resend.");
        }
    }

    public Map<String, Object> status(Long id, Role role) {
        AccountHolder a = find(id, role);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("verified", a.isVerified());
        m.put("emailVerified", a.getEmailVerifiedAt() != null);
        m.put("phoneVerified", a.getPhoneVerifiedAt() != null);
        m.put("phoneVerificationAvailable", sms.available());
        m.put("termsAccepted", termsVersion.equals(a.getTermsVersion()));
        m.put("currentTermsVersion", termsVersion);
        return m;
    }

    // ------------------------------------------------------------ passwords --

    /**
     * "Forgot password". Always looks the same to the caller, whether or not
     * the account exists, so it can't be used to find out who has one.
     */
    @Transactional
    public void forgotPassword(String emailAddress, Role role, Channel channel) {
        if (role != Role.COMMUTER && role != Role.DRIVER) return;
        Optional<? extends AccountHolder> found = findByEmail(emailAddress, role).filter(a -> a.getDeletedAt() == null);
        if (found.isEmpty()) {
            log.info("account.reset.unknown_account role={}", role);
            return;
        }
        AccountHolder a = found.get();
        if (!limitSends(a, Purpose.RESET_PASSWORD, false)) return;
        try {
            if (channel == Channel.SMS && sms.available()) {
                sendPhoneCode(a, Purpose.RESET_PASSWORD);
            } else {
                String token = newToken(a, Purpose.RESET_PASSWORD, RESET_LINK_TTL);
                email.send(a.getEmail(), "Reset your e-Tekisi password",
                        "Hi " + a.getFullName() + ",\n\n"
                                + "Someone asked to reset the password for your e-Tekisi account. To choose a new one, open:\n\n"
                                + publicUrl + "/reset-password?token=" + token + "\n\n"
                                + "This link works for 1 hour. If it wasn't you, ignore this email: your password stays the same.\n");
            }
            log.info("account.reset.sent role={} id={} channel={}", role, a.getId(), channel);
        } catch (DeliveryException e) {
            log.error("account.reset.delivery_failed role={} id={} error={}", role, a.getId(), e.getMessage());
        }
    }

    /** With the emailed link's token. */
    @Transactional
    public void resetWithToken(String token, String newPassword) {
        AccountToken t = tokens.findFirstBySecretHashAndChannel(sha256(token == null ? "" : token), Channel.EMAIL)
                .filter(x -> x.getPurpose() == Purpose.RESET_PASSWORD)
                .orElseThrow(() -> ApiException.badRequest("INVALID_LINK", "This reset link isn't valid. Ask for a new one."));
        if (t.getUsedAt() != null || t.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.badRequest("LINK_EXPIRED", "This reset link has expired or was already used. Ask for a new one.");
        }
        AccountHolder a = find(t.getOwnerId(), t.getOwnerRole());
        setPassword(a, newPassword);
        if (a.getEmailVerifiedAt() == null) a.setEmailVerifiedAt(Instant.now()); // they proved they read the inbox
        save(a);
        tokens.invalidate(a.getId(), t.getOwnerRole(), Purpose.RESET_PASSWORD, Instant.now());
        log.info("account.password_reset role={} id={} channel=EMAIL", t.getOwnerRole(), a.getId());
    }

    /** With the SMS code. */
    @Transactional(noRollbackFor = ApiException.class)
    public void resetWithCode(String emailAddress, Role role, String code, String newPassword) {
        AccountHolder a = findByEmail(emailAddress, role).filter(x -> x.getDeletedAt() == null)
                .orElseThrow(() -> ApiException.badRequest("INVALID_CODE", "That code isn't right or has expired."));
        policy.check(newPassword, a.getEmail(), a.getFullName()); // before using up an attempt
        checkCode(a, role, Purpose.RESET_PASSWORD, code);
        setPassword(a, newPassword);
        if (a.getPhoneVerifiedAt() == null) a.setPhoneVerifiedAt(Instant.now());
        save(a);
        log.info("account.password_reset role={} id={} channel=SMS", role, a.getId());
    }

    @Transactional
    public void changePassword(Long id, Role role, String current, String newPassword) {
        AccountHolder a = find(id, role);
        if (current == null || !encoder.matches(current, a.getPassword())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PASSWORD_INCORRECT", "Your current password isn't right.", null);
        }
        setPassword(a, newPassword);
        save(a);
        log.info("account.password_changed role={} id={}", role, id);
    }

    private void setPassword(AccountHolder a, String newPassword) {
        policy.check(newPassword, a.getEmail(), a.getFullName());
        a.setPassword(encoder.encode(newPassword));
        a.setCredentialsChangedAt(Instant.now());
    }

    // -------------------------------------------------------------- secrets --

    private void sendEmailVerification(AccountHolder a) {
        String token = newToken(a, Purpose.VERIFY_EMAIL, EMAIL_VERIFY_TTL);
        email.send(a.getEmail(), "Confirm your email for e-Tekisi",
                "Hi " + a.getFullName() + ",\n\n"
                        + "Please confirm this is your email address by opening:\n\n"
                        + publicUrl + "/verify-email?token=" + token + "\n\n"
                        + "The link works for 24 hours. If you didn't sign up for e-Tekisi, ignore this email.\n");
    }

    private void sendPhoneCode(AccountHolder a, Purpose purpose) {
        String to = PhoneNumbers.toE164(a.getPhoneNumber());
        if (to == null) {
            throw ApiException.badRequest("PHONE_NOT_MOBILE", "We can't send an SMS to " + a.getPhoneNumber() + ".");
        }
        Role role = roleOf(a);
        tokens.invalidate(a.getId(), role, purpose, Instant.now()); // only the newest code works
        String code = String.format("%06d", random.nextInt(1_000_000));
        AccountToken t = new AccountToken();
        t.setOwnerId(a.getId());
        t.setOwnerRole(role);
        t.setPurpose(purpose);
        t.setChannel(Channel.SMS);
        t.setSecretHash(codeHash(a.getId(), role, purpose, code));
        t.setExpiresAt(Instant.now().plus(CODE_TTL));
        tokens.save(t);
        String what = purpose == Purpose.RESET_PASSWORD ? "password reset code" : "verification code";
        sms.send(to, "Your e-Tekisi " + what + " is " + code + ". It expires in 10 minutes. Never share it with anyone.");
        log.info("account.code_sent role={} id={} purpose={} to={}", role, a.getId(), purpose, PhoneNumbers.masked(to));
    }

    private String newToken(AccountHolder a, Purpose purpose, Duration ttl) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        AccountToken t = new AccountToken();
        t.setOwnerId(a.getId());
        t.setOwnerRole(roleOf(a));
        t.setPurpose(purpose);
        t.setChannel(Channel.EMAIL);
        t.setSecretHash(sha256(token));
        t.setExpiresAt(Instant.now().plus(ttl));
        tokens.save(t);
        return token;
    }

    private void checkCode(AccountHolder a, Role role, Purpose purpose, String code) {
        AccountToken t = tokens
                .findFirstByOwnerIdAndOwnerRoleAndPurposeAndChannelAndUsedAtIsNullAndExpiresAtAfterOrderByIdDesc(
                        a.getId(), role, purpose, Channel.SMS, Instant.now())
                .orElseThrow(() -> ApiException.badRequest("INVALID_CODE", "That code isn't right or has expired. Ask for a new one."));
        if (t.getAttempts() >= MAX_CODE_ATTEMPTS) {
            throw ApiException.badRequest("INVALID_CODE", "Too many wrong tries. Ask for a new code.");
        }
        String given = code == null ? "" : code.replaceAll("\\s", "");
        boolean ok = MessageDigest.isEqual(
                codeHash(a.getId(), role, purpose, given).getBytes(StandardCharsets.US_ASCII),
                t.getSecretHash().getBytes(StandardCharsets.US_ASCII));
        if (!ok) {
            t.setAttempts(t.getAttempts() + 1);
            tokens.save(t);
            throw ApiException.badRequest("INVALID_CODE", "That code isn't right or has expired. Ask for a new one.");
        }
        tokens.invalidate(a.getId(), role, purpose, Instant.now());
    }

    /**
     * Send limits. With {@code loud} a limited caller gets 429; otherwise (the
     * anonymous "forgot password" form) the send is silently skipped.
     */
    private boolean limitSends(AccountHolder a, Purpose purpose, boolean loud) {
        List<AccountToken> lastHour = tokens.findByOwnerIdAndOwnerRoleAndPurposeAndCreatedAtAfterOrderByCreatedAtDesc(
                a.getId(), roleOf(a), purpose, Instant.now().minus(Duration.ofHours(1)));
        boolean tooSoon = !lastHour.isEmpty() && lastHour.get(0).getCreatedAt().isAfter(Instant.now().minus(RESEND_GAP));
        boolean tooMany = lastHour.size() >= MAX_SENDS_PER_HOUR;
        if (!tooSoon && !tooMany) return true;
        if (loud) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS",
                    tooMany ? "That's a lot of messages. Try again in an hour." : "Wait a minute before asking for another one.", null);
        }
        log.info("account.send.limited role={} id={} purpose={}", roleOf(a), a.getId(), purpose);
        return false;
    }

    private void requireSms() {
        if (!sms.available()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SMS_UNAVAILABLE",
                    "Phone verification isn't available yet. Verify your email address instead.", null);
        }
    }

    private static void deliver(Runnable send) {
        try {
            send.run();
        } catch (DeliveryException e) {
            log.error("account.delivery_failed error={}", e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DELIVERY_FAILED",
                    "We couldn't send that just now. Please try again in a few minutes.", null);
        }
    }

    private static String codeHash(Long id, Role role, Purpose purpose, String code) {
        return sha256(role + ":" + id + ":" + purpose + ":" + code);
    }

    static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Expired links and codes are useless; drop them after a week (kept that long for support questions). */
    @SchedulerLock(name = "account-token-purge", lockAtMostFor = "PT30M")
    @Scheduled(cron = "${tekisi.account-tokens.purge-cron:0 45 3 * * *}")
    @Transactional
    public void purgeExpired() {
        int n = tokens.deleteExpiredBefore(Instant.now().minus(Duration.ofDays(7)));
        if (n > 0) log.info("account.tokens.purged deleted={}", n);
    }
}
