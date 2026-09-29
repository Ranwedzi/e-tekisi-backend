package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    // This finds all messages for a specific trip (booking)
    // and sorts them from oldest to newest so the chat makes sense.
    List<Message> findByBookingIdOrderByTimestampAsc(Long bookingId);
}