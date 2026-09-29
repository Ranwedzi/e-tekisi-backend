package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.TaxiRank;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RankRepository extends JpaRepository<TaxiRank, Long> {
}