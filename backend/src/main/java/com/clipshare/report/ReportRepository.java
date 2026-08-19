package com.clipshare.report;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    // "Pendiente" = todavía no resuelto (ni DISMISSED ni ACTIONED) — un reporte en
    // UNDER_REVIEW (contra-notificación ya presentada) sigue necesitando la decisión final
    // de un admin, así que también cuenta como pendiente acá.
    @EntityGraph(attributePaths = {"clip", "clip.owner"})
    @Query("SELECT r FROM Report r WHERE r.status IN ('OPEN', 'UNDER_REVIEW')")
    Page<Report> findPending(Pageable pageable);
}
