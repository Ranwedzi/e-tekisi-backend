package com.etekisi.etekisi.controller;

import com.etekisi.etekisi.dto.api.ApiRecords.JoinQueueRequest;
import com.etekisi.etekisi.model.Driver;
import com.etekisi.etekisi.model.QueueTicket;
import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.model.TaxiRank;
import com.etekisi.etekisi.repository.DriverRepository;
import com.etekisi.etekisi.repository.QueueRepository;
import com.etekisi.etekisi.repository.RankRepository;
import com.etekisi.etekisi.security.CurrentUser;
import com.etekisi.etekisi.web.ApiException;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/** Rank queues. Drivers manage only their own ticket; the driver id comes from the token. */
@RestController
@RequestMapping("/api/queue")
public class QueueController {

    static final String WAITING = "WAITING";
    static final String SERVING = "SERVING";
    static final String COMPLETED = "COMPLETED";
    private static final List<String> LIVE = List.of(WAITING, SERVING);

    private final QueueRepository queueRepository;
    private final RankRepository rankRepository;
    private final DriverRepository driverRepository;
    private final CurrentUser currentUser;

    public QueueController(QueueRepository queueRepository, RankRepository rankRepository,
                           DriverRepository driverRepository, CurrentUser currentUser) {
        this.queueRepository = queueRepository;
        this.rankRepository = rankRepository;
        this.driverRepository = driverRepository;
        this.currentUser = currentUser;
    }

    @PostMapping("/join")
    @Transactional
    public QueueTicket joinQueue(@Valid @RequestBody JoinQueueRequest req) {
        Long driverId = currentUser.requireRole(Role.DRIVER);
        // A driver holds at most one live ticket, at any rank.
        var existing = queueRepository.findFirstByDriverIdAndStatusIn(driverId, LIVE);
        if (existing.isPresent()) return existing.get();

        Driver driver = driverRepository.findById(driverId).orElseThrow(() -> ApiException.notFound("Driver"));
        com.etekisi.etekisi.service.AccountSecurityService.requireVerified(driver);
        if (!driver.isApproved()) {
            throw ApiException.conflict("DRIVER_NOT_APPROVED", "Your driver account must be approved before you can join a queue.");
        }
        QueueTicket ticket = new QueueTicket();
        ticket.setDriverId(driverId);
        ticket.setDriverName(driver.getFullName());
        ticket.setTaxiRank(req.taxiRank().trim());
        ticket.setJoinedAt(LocalDateTime.now());
        ticket.setStatus(WAITING);
        return queueRepository.save(ticket);
    }

    /** The rank's live queue: the driver currently loading first, then waiting drivers in order. */
    @GetMapping("/status/{rank}")
    public List<QueueTicket> getRankQueue(@PathVariable String rank) {
        return queueRepository.findByTaxiRankAndStatusInOrderByJoinedAtAsc(rank, LIVE).stream()
                .sorted(Comparator.comparing((QueueTicket t) -> SERVING.equals(t.getStatus()) ? 0 : 1)
                        .thenComparing(QueueTicket::getJoinedAt))
                .toList();
    }

    @GetMapping("/ranks")
    public List<TaxiRank> getAvailableRanks() {
        return rankRepository.findAll();
    }

    @PutMapping("/skip/{ticketId}")
    @Transactional
    public QueueTicket skipTurn(@PathVariable Long ticketId) {
        QueueTicket ticket = ownTicket(ticketId);
        if (!WAITING.equals(ticket.getStatus())) {
            throw ApiException.conflict("TICKET_NOT_WAITING", "Only a waiting ticket can skip its turn.");
        }
        // Reset time to "now" to put them at the back of the line.
        ticket.setJoinedAt(LocalDateTime.now());
        ticket.setSkipCount(ticket.getSkipCount() + 1);
        return ticket;
    }

    @PutMapping("/serve/{ticketId}")
    @Transactional
    public QueueTicket startServing(@PathVariable Long ticketId) {
        QueueTicket ticket = ownTicket(ticketId);
        if (!WAITING.equals(ticket.getStatus())) {
            throw ApiException.conflict("TICKET_NOT_WAITING", "This ticket is not waiting.");
        }
        List<QueueTicket> waiting = queueRepository.findByTaxiRankAndStatusOrderByJoinedAtAsc(ticket.getTaxiRank(), WAITING);
        if (waiting.isEmpty() || !waiting.get(0).getId().equals(ticket.getId())) {
            throw ApiException.conflict("NOT_FIRST_IN_QUEUE", "Only the driver at the front of the queue can start loading.");
        }
        ticket.setStatus(SERVING);
        ticket.setServedAt(LocalDateTime.now());
        return ticket;
    }

    @PutMapping("/complete/{ticketId}")
    @Transactional
    public void completeTurn(@PathVariable Long ticketId) {
        QueueTicket ticket = ownTicket(ticketId);
        ticket.setStatus(COMPLETED);
    }

    private QueueTicket ownTicket(Long ticketId) {
        QueueTicket ticket = queueRepository.findById(ticketId).orElseThrow(() -> ApiException.notFound("Queue ticket"));
        if (!currentUser.isStaff() && !ticket.getDriverId().equals(currentUser.requireRole(Role.DRIVER))) {
            throw new AccessDeniedException("Not your queue ticket");
        }
        return ticket;
    }
}
