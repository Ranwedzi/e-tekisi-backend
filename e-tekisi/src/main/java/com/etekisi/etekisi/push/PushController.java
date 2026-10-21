package com.etekisi.etekisi.push;

import com.etekisi.etekisi.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * {@code GET /api/push/key} (public): the server's VAPID public key, for
 * {@code pushManager.subscribe({applicationServerKey})}. Signed-in users then
 * register the subscription with {@code POST /api/me/push} and remove it with
 * {@code POST /api/me/push/unsubscribe} (also on sign-out).
 */
@RestController
public class PushController {

    public record Keys(@NotBlank @Size(max = 200) String p256dh, @NotBlank @Size(max = 100) String auth) {}
    public record SubscribeRequest(@NotBlank @Size(max = 1000) String endpoint, @NotNull @Valid Keys keys) {}
    public record EndpointRequest(@NotBlank @Size(max = 1000) String endpoint) {}

    private final PushService push;
    private final VapidKeys vapid;
    private final CurrentUser currentUser;

    public PushController(PushService push, VapidKeys vapid, CurrentUser currentUser) {
        this.push = push;
        this.vapid = vapid;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/push/key")
    public Map<String, Object> key() {
        return vapid.enabled() ? Map.of("enabled", true, "publicKey", vapid.publicKey()) : Map.of("enabled", false);
    }

    @GetMapping("/api/me/push")
    public Map<String, Object> status() {
        return push.status(currentUser.role(), currentUser.id());
    }

    @PostMapping("/api/me/push")
    public Map<String, Object> subscribe(@Valid @RequestBody SubscribeRequest body) {
        push.subscribe(currentUser.role(), currentUser.id(), body.endpoint(), body.keys().p256dh(), body.keys().auth());
        return Map.of("status", "SUBSCRIBED");
    }

    @PostMapping("/api/me/push/unsubscribe")
    public Map<String, Object> unsubscribe(@Valid @RequestBody EndpointRequest body) {
        push.unsubscribe(currentUser.role(), currentUser.id(), body.endpoint());
        return Map.of("status", "UNSUBSCRIBED");
    }
}
