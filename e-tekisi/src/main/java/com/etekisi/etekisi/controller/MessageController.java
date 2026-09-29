package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.model.Message;
import com.etekisi.etekisi.repository.MessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/messages")
public class MessageController {
    @Autowired private MessageRepository messageRepository;

    @PostMapping("/send")
    public Message sendMessage(@RequestBody Message message) {
        return messageRepository.save(message);
    }

    @GetMapping("/{bookingId}")
    public List<Message> getChat(@PathVariable Long bookingId) {
        return messageRepository.findByBookingIdOrderByTimestampAsc(bookingId);
    }
}