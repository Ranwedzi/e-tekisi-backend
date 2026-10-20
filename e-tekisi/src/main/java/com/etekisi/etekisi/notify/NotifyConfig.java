package com.etekisi.etekisi.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Picks how account emails and SMS codes are delivered.
 *
 * <p><b>Email:</b> SMTP when {@code spring.mail.host} is set (Spring Boot then
 * provides a {@link JavaMailSender}; any provider works: SendGrid, Mailgun,
 * Amazon SES, Google Workspace, Mailpit in dev). Otherwise, with
 * {@code tekisi.notify.log-fallback=true} (dev), messages are only logged.
 * The prod profile sets {@code tekisi.notify.email-required=true}, so the app
 * won't start without SMTP: people couldn't verify or reset otherwise.
 *
 * <p><b>SMS:</b> {@code tekisi.sms.provider} = {@code none} (phone
 * verification not offered), {@code log} (dev), {@code bulksms},
 * {@code clickatell}, {@code twilio} or {@code generic} (POST
 * {@code {"to","message"}} as JSON to {@code tekisi.sms.url}).
 */
@Configuration
public class NotifyConfig {

    private static final Logger log = LoggerFactory.getLogger(NotifyConfig.class);

    @Bean
    public EmailSender emailSender(ObjectProvider<JavaMailSender> mail,
                                   @Value("${tekisi.mail.from:e-Tekisi <no-reply@localhost>}") String from,
                                   @Value("${tekisi.notify.log-fallback:false}") boolean logFallback,
                                   @Value("${tekisi.notify.email-required:false}") boolean required) {
        JavaMailSender smtp = mail.getIfAvailable();
        if (smtp != null) {
            log.info("notify.email provider=smtp");
            return (to, subject, text) -> {
                SimpleMailMessage m = new SimpleMailMessage();
                m.setFrom(from);
                m.setTo(to);
                m.setSubject(subject);
                m.setText(text);
                try {
                    smtp.send(m);
                } catch (MailException e) {
                    throw new DeliveryException("SMTP send failed: " + e.getMessage(), e);
                }
            };
        }
        if (required) {
            throw new IllegalStateException("No SMTP server configured. Set SPRING_MAIL_HOST (and _PORT, _USERNAME, "
                    + "_PASSWORD) so people can verify their email and reset passwords.");
        }
        if (logFallback) {
            log.warn("notify.email provider=log (dev only: emails are written to the log, not sent)");
            return new LogEmailSender();
        }
        log.warn("notify.email provider=none: verification and reset emails can't be sent");
        return new EmailSender() {
            @Override public void send(String to, String subject, String text) {
                throw new DeliveryException("Email isn't configured");
            }
            @Override public boolean available() { return false; }
        };
    }

    @Bean
    public SmsSender smsSender(@Value("${tekisi.sms.provider:none}") String provider,
                               @Value("${tekisi.sms.url:}") String url,
                               @Value("${tekisi.sms.username:}") String username,
                               @Value("${tekisi.sms.password:}") String password,
                               @Value("${tekisi.sms.from:}") String from) {
        String p = provider == null ? "none" : provider.trim().toLowerCase();
        log.info("notify.sms provider={}", p);
        if (p.equals("none") || p.isEmpty()) {
            return new SmsSender() {
                @Override public void send(String to, String text) { throw new DeliveryException("SMS isn't configured"); }
                @Override public boolean available() { return false; }
            };
        }
        if (p.equals("log")) return new LogSmsSender();

        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(5));
        timeouts.setReadTimeout(Duration.ofSeconds(10));
        RestClient http = RestClient.builder().requestFactory(timeouts).build();
        String basic = "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));

        return switch (p) {
            // https://www.bulksms.com/developer/json/v1/ : basic auth with an API token id/secret.
            case "bulksms" -> (to, text) -> call(() -> http.post()
                    .uri(url.isBlank() ? "https://api.bulksms.com/v1/messages" : url)
                    .header(HttpHeaders.AUTHORIZATION, basic)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("to", to, "body", text))
                    .retrieve().toBodilessEntity());
            // https://docs.clickatell.com/channels/sms-channels/sms-api-reference/ : API key in Authorization.
            case "clickatell" -> (to, text) -> call(() -> http.post()
                    .uri(url.isBlank() ? "https://platform.clickatell.com/v1/message" : url)
                    .header(HttpHeaders.AUTHORIZATION, password)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("messages", List.of(Map.of("channel", "sms", "to", to.substring(1), "content", text))))
                    .retrieve().toBodilessEntity());
            // https://www.twilio.com/docs/messaging/api/message-resource : username = Account SID, password = auth token.
            case "twilio" -> (to, text) -> {
                MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                form.add("To", to);
                form.add("From", from);
                form.add("Body", text);
                call(() -> http.post()
                        .uri(url.isBlank() ? "https://api.twilio.com/2010-04-01/Accounts/" + username + "/Messages.json" : url)
                        .header(HttpHeaders.AUTHORIZATION, basic)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve().toBodilessEntity());
            };
            case "generic" -> {
                if (url.isBlank()) throw new IllegalStateException("tekisi.sms.provider=generic needs SMS_URL");
                yield (to, text) -> call(() -> {
                    RestClient.RequestBodySpec req = http.post().uri(url).contentType(MediaType.APPLICATION_JSON);
                    if (!password.isBlank()) req = req.header(HttpHeaders.AUTHORIZATION, password);
                    req.body(Map.of("to", to, "message", text, "from", from)).retrieve().toBodilessEntity();
                });
            }
            default -> throw new IllegalStateException("Unknown SMS_PROVIDER '" + provider
                    + "' (use none, log, bulksms, clickatell, twilio or generic)");
        };
    }

    private static void call(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            throw new DeliveryException("SMS send failed: " + e.getMessage(), e);
        }
    }

    /** Dev only. Keeps the last 50 messages so tests can read them. */
    public static class LogEmailSender implements EmailSender {
        private final Deque<String[]> recent = new ArrayDeque<>();

        @Override
        public synchronized void send(String to, String subject, String text) {
            log.info("notify.email.logged to={} subject=\"{}\"\n{}", to, subject, text);
            recent.addFirst(new String[]{to, subject, text});
            while (recent.size() > 50) recent.removeLast();
        }

        /** Newest first: [to, subject, text]. */
        public synchronized List<String[]> recent() {
            return new ArrayList<>(recent);
        }
    }

    /** Dev only. Keeps the last 50 messages so tests can read them. */
    public static class LogSmsSender implements SmsSender {
        private final Deque<String[]> recent = new ArrayDeque<>();

        @Override
        public synchronized void send(String to, String text) {
            log.info("notify.sms.logged to={} text=\"{}\"", to, text);
            recent.addFirst(new String[]{to, text});
            while (recent.size() > 50) recent.removeLast();
        }

        /** Newest first: [to, text]. */
        public synchronized List<String[]> recent() {
            return new ArrayList<>(recent);
        }
    }
}
