package com.etekisi.etekisi.agent.dispatch;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DispatchRecommendationRepository extends JpaRepository<DispatchRecommendation, Long> {

    List<DispatchRecommendation> findByBookingIdOrderByCreatedAtDesc(Long bookingId);

    List<DispatchRecommendation> findByStatusOrderByCreatedAtDesc(DispatchRecommendation.Status status);
}
