package com.clipshare.comment;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommentAttachmentRepository extends JpaRepository<CommentAttachment, UUID> {

    @EntityGraph(attributePaths = {"uploadedBy", "referencedClip"})
    @Query("SELECT a FROM CommentAttachment a WHERE a.id = :id")
    Optional<CommentAttachment> findByIdWithUploader(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"referencedClip"})
    List<CommentAttachment> findByCommentId(UUID commentId);

    // Batch para listar una página de comentarios sin N+1 (ver CommentController).
    @EntityGraph(attributePaths = {"referencedClip"})
    List<CommentAttachment> findByCommentIdIn(List<UUID> commentIds);
}
