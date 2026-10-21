package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.Role;
import com.etekisi.etekisi.model.TripRating;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TripRatingRepository extends JpaRepository<TripRating, Long> {

    boolean existsByBookingIdAndRaterRole(Long bookingId, Role raterRole);

    List<TripRating> findByBookingIdIn(Collection<Long> bookingIds);

    List<TripRating> findTop20ByRateeRoleAndRateeIdOrderByCreatedAtDesc(Role rateeRole, Long rateeId);
}
