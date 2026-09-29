package com.etekisi.etekisi.repository;

import com.etekisi.etekisi.model.Driver;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DriverRepository extends JpaRepository<Driver, Long> {
    java.util.Optional<Driver> findByEmail(String email);
}