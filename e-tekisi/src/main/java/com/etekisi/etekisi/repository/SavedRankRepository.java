package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.SavedRank;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SavedRankRepository extends JpaRepository<SavedRank, Long> {
    List<SavedRank> findByCommuterIdOrderByCreatedAtDesc(Long commuterId);

    long deleteByCommuterId(Long commuterId);
}
