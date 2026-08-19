package com.clipshare.moderation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CsamHashMatchRepository extends JpaRepository<CsamHashMatch, UUID> {
}
