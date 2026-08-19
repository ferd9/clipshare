package com.clipshare.comment;

import com.clipshare.clip.Clip;
import com.clipshare.user.User;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Comentario de un clip publicado, autor con cuenta (USER) o invitado (GUEST) — docs/SPEC.md
 * sección 11. {@code ipHash}/{@code anonSessionId} nunca son el nombre visible de nadie: solo
 * sirven para rate-limiting y shadow-ban (ver CommentRateLimitService/ShadowBanService).
 */
@Entity
@Table(name = "comments")
public class Comment {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clip_id", nullable = false)
    private Clip clip;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_comment_id")
    private Comment parentComment;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "author_type", nullable = false)
    private CommentAuthorType authorType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "guest_display_name")
    private String guestDisplayName;

    @Column(nullable = false, length = 500)
    private String body;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private CommentStatus status = CommentStatus.VISIBLE;

    @Column(name = "ip_hash", nullable = false)
    private String ipHash;

    @Column(name = "anon_session_id")
    private UUID anonSessionId;

    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "like_count", nullable = false)
    private int likeCount = 0;

    @Column(name = "report_count", nullable = false)
    private int reportCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Comment() {
        // JPA
    }

    public Comment(Clip clip, Comment parentComment, CommentAuthorType authorType, User user,
                   String guestDisplayName, String body, String ipHash, UUID anonSessionId, String contentHash) {
        this.clip = clip;
        this.parentComment = parentComment;
        this.authorType = authorType;
        this.user = user;
        this.guestDisplayName = guestDisplayName;
        this.body = body;
        this.ipHash = ipHash;
        this.anonSessionId = anonSessionId;
        this.contentHash = contentHash;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Clip getClip() {
        return clip;
    }

    public Comment getParentComment() {
        return parentComment;
    }

    public CommentAuthorType getAuthorType() {
        return authorType;
    }

    public User getUser() {
        return user;
    }

    public String getGuestDisplayName() {
        return guestDisplayName;
    }

    public String getBody() {
        return body;
    }

    public CommentStatus getStatus() {
        return status;
    }

    public void setStatus(CommentStatus status) {
        this.status = status;
    }

    public String getIpHash() {
        return ipHash;
    }

    public UUID getAnonSessionId() {
        return anonSessionId;
    }

    public String getContentHash() {
        return contentHash;
    }

    public int getLikeCount() {
        return likeCount;
    }

    public int getReportCount() {
        return reportCount;
    }

    public void setReportCount(int reportCount) {
        this.reportCount = reportCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
