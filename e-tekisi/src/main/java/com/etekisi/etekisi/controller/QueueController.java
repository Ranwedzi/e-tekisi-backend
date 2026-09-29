package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.model.QueueTicket;
import com.etekisi.etekisi.model.TaxiRank;
import com.etekisi.etekisi.repository.QueueRepository;
import com.etekisi.etekisi.repository.RankRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/queue")
public class QueueController {

    @Autowired private QueueRepository queueRepository;

    @Autowired private RankRepository rankRepository;

    @PostMapping("/join")
    public QueueTicket joinQueue(@RequestBody QueueTicket ticket) {
        // Prevent duplicate active tickets for the same driver
        var existing = queueRepository.findByDriverIdAndStatus(ticket.getDriverId(), "WAITING");
        if (existing.isPresent()) return existing.get();

        ticket.setJoinedAt(LocalDateTime.now());
        ticket.setStatus("WAITING");
        return queueRepository.save(ticket);
    }

    @GetMapping("/status/{rank}")
    public List<QueueTicket> getRankQueue(@PathVariable String rank) {
        // Returns both WAITING and SERVING drivers sorted by time
        return queueRepository.findByTaxiRankAndStatusOrderByJoinedAtAsc(rank, "WAITING");
    }

    @GetMapping("/ranks")
    public List<TaxiRank> getAvailableRanks() {
        return rankRepository.findAll();
    }

    @PutMapping("/skip/{ticketId}")
    public QueueTicket skipTurn(@PathVariable Long ticketId) {
        QueueTicket ticket = queueRepository.findById(ticketId).orElseThrow();
        // Reset time to "now" to put them at the back of the line
        ticket.setJoinedAt(LocalDateTime.now());
        ticket.setSkipCount(ticket.getSkipCount() + 1);
        return queueRepository.save(ticket);
    }

    @PutMapping("/serve/{ticketId}")
    public QueueTicket startServing(@PathVariable Long ticketId) {
        QueueTicket ticket = queueRepository.findById(ticketId).orElseThrow();
        ticket.setStatus("SERVING");
        ticket.setServedAt(LocalDateTime.now());
        return queueRepository.save(ticket);
    }

    @PutMapping("/complete/{ticketId}")
    public void completeTurn(@PathVariable Long ticketId) {
        QueueTicket ticket = queueRepository.findById(ticketId).orElseThrow();
        ticket.setStatus("COMPLETED");
        queueRepository.save(ticket);
    }
}