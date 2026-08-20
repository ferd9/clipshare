package com.clipshare.comment;

/** Coincide con {@code embed_platform VARCHAR(20)} — columna plana, no un ENUM nativo de
 * Postgres (a diferencia de otros enums del proyecto), ver V6__comment_attachments.sql. */
public enum EmbedPlatform {
    YOUTUBE, VIMEO, TWITCH, TIKTOK, FACEBOOK, INSTAGRAM
}
