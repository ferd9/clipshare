package com.clipshare.clip;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ClipRepository extends JpaRepository<Clip, UUID> {

    // @EntityGraph trae "owner" en la misma query: sin esto, mapear a DTO fuera de la
    // transacción (open-in-view: false) revienta con LazyInitializationException apenas
    // se toca clip.getOwner() — pasó exactamente eso en ClipUploadIntegrationTest.
    @EntityGraph(attributePaths = "owner")
    Page<Clip> findByModerationStatusAndVisibilityAndDeletedAtIsNull(
            ModerationStatus moderationStatus, ClipVisibility visibility, Pageable pageable);

    @EntityGraph(attributePaths = "owner")
    @Query("SELECT c FROM Clip c WHERE c.id = :id")
    Optional<Clip> findByIdWithOwner(@Param("id") UUID id);

    Optional<Clip> findByContentHashAndDeletedAtIsNull(String contentHash);
}
