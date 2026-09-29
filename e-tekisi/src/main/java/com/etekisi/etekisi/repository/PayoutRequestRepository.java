package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.PayoutRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PayoutRequestRepository extends JpaRepository<PayoutRequest, Long> {
    List<PayoutRequest> findByDriverIdOrderByCreatedAtDesc(Long driverId);
}
