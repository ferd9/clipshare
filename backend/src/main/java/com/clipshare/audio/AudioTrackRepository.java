package com.clipshare.audio;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AudioTrackRepository extends JpaRepository<AudioTrack, UUID> {

    @EntityGraph(attributePaths = "uploadedBy")
    @Query("SELECT a FROM AudioTrack a WHERE a.id = :id")
    Optional<AudioTrack> findByIdWithUploader(@Param("id") UUID id);

    Optional<AudioTrack> findByContentHash(String contentHash);
}
