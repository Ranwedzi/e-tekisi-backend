package com.etekisi.etekisi.realtime;

import com.etekisi.etekisi.security.CurrentUser;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/events}: the signed-in user's live-event stream
 * (text/event-stream). Events are named {@code change} with the topic as
 * data; see {@link LiveEvents}. Sign in with the usual Authorization header
 * (read the stream with fetch, not EventSource, which can't send it). The
 * stream ends after 30 minutes; clients reconnect.
 */
@RestController
public class LiveEventsController {

    private final LiveEvents events;
    private final CurrentUser currentUser;

    public LiveEventsController(LiveEvents events, CurrentUser currentUser) {
        this.events = events;
        this.currentUser = currentUser;
    }

    @GetMapping(path = "/api/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no"); // don't let a proxy hold events back
        return events.subscribe(currentUser.id(), currentUser.role());
    }
}
