package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.Message;
import com.etekisi.etekisi.model.Role;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    // Oldest first so the chat reads top to bottom.
    List<Message> findByBookingIdOrderByTimestampAsc(Long bookingId);

    List<Message> findBySenderIdAndSenderRoleOrderByTimestampAsc(Long senderId, Role senderRole);

    /** Account deletion: the other party keeps the thread, but not what this person wrote. */
    @Modifying
    @Query("update Message m set m.content = '[message removed]' where m.senderId = :senderId and m.senderRole = :role")
    int redactBySender(@Param("senderId") Long senderId, @Param("role") Role role);

    /** Retention: chat older than the cutoff. */
    @Modifying
    @Query("delete from Message m where m.timestamp < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
