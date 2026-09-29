package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.dto.api.ApiRecords.MessageDto;
import com.etekisi.etekisi.dto.api.ApiRecords.SendMessageRequest;
import com.etekisi.etekisi.model.Message;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.repository.MessageRepository;
import com.etekisi.etekisi.security.CurrentUser;
import com.etekisi.etekisi.service.BookingService;
import com.etekisi.etekisi.web.ApiException;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Trip chat between a booking's commuter and its assigned driver. */
@RestController
@RequestMapping("/api/messages")
public class MessageController {

    private final MessageRepository messages;
    private final BookingService bookings;
    private final CurrentUser currentUser;

    public MessageController(MessageRepository messages, BookingService bookings, CurrentUser currentUser) {
        this.messages = messages;
        this.bookings = bookings;
        this.currentUser = currentUser;
    }

    /** Sender id and role come from the token; any senderId in the body is ignored. */
    @PostMapping("/send")
    public MessageDto sendMessage(@Valid @RequestBody SendMessageRequest req) {
        if (req.bookingId() == null) {
            throw ApiException.badRequest("INVALID_MESSAGE", "bookingId is required");
        }
        Long callerId = currentUser.id();
        Role role = currentUser.role();
        bookings.requireParticipant(req.bookingId(), callerId, role);

        Message m = new Message();
        m.setBookingId(req.bookingId());
        m.setSenderId(callerId);
        m.setSenderRole(role);
        m.setContent(req.content().trim());
        return MessageDto.from(messages.save(m));
    }

    @GetMapping("/{bookingId}")
    public List<MessageDto> getChat(@PathVariable Long bookingId) {
        bookings.requireParticipant(bookingId, currentUser.id(), currentUser.role());
        return messages.findByBookingIdOrderByTimestampAsc(bookingId).stream().map(MessageDto::from).toList();
    }
}
