package com.clipshare.report;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {

    @EntityGraph(attributePaths = {"clip", "clip.owner"})
    @Query("SELECT r FROM Report r WHERE r.id = :id")
    Optional<Report> findByIdWithClip(@Param("id") UUID id);
}
