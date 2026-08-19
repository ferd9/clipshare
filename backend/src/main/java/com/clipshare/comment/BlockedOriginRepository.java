package com.clipshare.comment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BlockedOriginRepository extends JpaRepository<BlockedOrigin, UUID> {

    @Query("SELECT b FROM BlockedOrigin b WHERE (b.ipHash = :ipHash OR b.anonSessionId = :anonSessionId) " +
            "AND (b.blockedUntil IS NULL OR b.blockedUntil > :now)")
    List<BlockedOrigin> findActive(@Param("ipHash") String ipHash, @Param("anonSessionId") UUID anonSessionId,
                                    @Param("now") Instant now);
}
