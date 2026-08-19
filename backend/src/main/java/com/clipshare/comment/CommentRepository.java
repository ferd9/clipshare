package com.clipshare.comment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommentRepository extends JpaRepository<Comment, UUID> {

    @EntityGraph(attributePaths = {"user"})
    @Query("SELECT c FROM Comment c WHERE c.id = :id")
    Optional<Comment> findByIdWithUser(@Param("id") UUID id);

    /**
     * Comentarios visibles de un clip para un visitante dado: VISIBLE para cualquiera, más
     * los propios HIDDEN por shadow-ban (el autor los sigue viendo con normalidad — docs/SPEC.md
     * sección 11.6 — pero nadie más). {@code viewerUserId}/{@code viewerAnonSessionId} pueden
     * ser NULL sin romper la comparación porque el operador es siempre {@code =} contra un
     * parámetro, nunca contra la columna directamente.
     */
    @EntityGraph(attributePaths = {"user"})
    @Query("SELECT c FROM Comment c WHERE c.clip.id = :clipId AND c.deletedAt IS NULL AND (" +
            "c.status = 'VISIBLE' OR (c.status = 'HIDDEN' AND (" +
            "(:viewerUserId IS NOT NULL AND c.user.id = :viewerUserId) OR " +
            "(:viewerAnonSessionId IS NOT NULL AND c.anonSessionId = :viewerAnonSessionId)))) " +
            "ORDER BY c.createdAt ASC")
    Page<Comment> findVisibleForViewer(@Param("clipId") UUID clipId, @Param("viewerUserId") UUID viewerUserId,
                                        @Param("viewerAnonSessionId") UUID viewerAnonSessionId, Pageable pageable);

    @EntityGraph(attributePaths = {"user", "clip"})
    @Query("SELECT c FROM Comment c WHERE c.status = 'PENDING_REVIEW' AND c.deletedAt IS NULL ORDER BY c.createdAt ASC")
    Page<Comment> findPendingReview(Pageable pageable);

    @Query("SELECT COUNT(DISTINCT c.ipHash) FROM Comment c WHERE c.contentHash = :contentHash AND c.createdAt >= :since")
    long countDistinctOriginsWithContentHashSince(@Param("contentHash") String contentHash, @Param("since") Instant since);

    @Query("SELECT DISTINCT c.ipHash FROM Comment c WHERE c.contentHash = :contentHash AND c.createdAt >= :since")
    List<String> findDistinctIpHashesWithContentHashSince(@Param("contentHash") String contentHash, @Param("since") Instant since);
}
