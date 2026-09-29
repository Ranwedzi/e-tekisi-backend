package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    // Oldest first so the chat reads top to bottom.
    List<Message> findByBookingIdOrderByTimestampAsc(Long bookingId);
}
