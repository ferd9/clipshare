package com.clipshare.comment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 6c (docs/SPEC.md sección 11.10). Twitch no llama a ninguna API (no tiene oEmbed
 * público) así que se puede probar sin red; YouTube/Vimeo/TikTok sí hacen una llamada HTTP
 * real a sus endpoints de oEmbed — cubiertos acá solo en el caso "no reconocido" (sin red) y
 * verificados de punta a punta contra el stack real en la verificación manual de la fase.
 */
class VideoEmbedResolverServiceTest {

    private final VideoEmbedResolverService service = new VideoEmbedResolverService(List.of(
            new YoutubeEmbedResolver(new OembedHttpClient()),
            new VimeoEmbedResolver(new OembedHttpClient()),
            new TwitchEmbedResolver(),
            new TiktokEmbedResolver(new OembedHttpClient()),
            new FacebookEmbedResolver(),
            new InstagramEmbedResolver()
    ));

    @Test
    void twitchVideoUrlIsEmbeddableWithoutAnyHttpCall() {
        EmbedResolution result = service.resolve("https://www.twitch.tv/somechannel");
        assertThat(result.platform()).isEqualTo(EmbedPlatform.TWITCH);
        assertThat(result.externalId()).isEqualTo("somechannel");
        assertThat(result.embeddable()).isTrue();
    }

    @Test
    void twitchClipsSubdomainIsRecognized() {
        EmbedResolution result = service.resolve("https://clips.twitch.tv/SomeClipSlug");
        assertThat(result.platform()).isEqualTo(EmbedPlatform.TWITCH);
        assertThat(result.externalId()).isEqualTo("SomeClipSlug");
    }

    @Test
    void facebookIsLabeledButNeverEmbeddable() {
        EmbedResolution result = service.resolve("https://www.facebook.com/watch/?v=123456");
        assertThat(result.platform()).isEqualTo(EmbedPlatform.FACEBOOK);
        assertThat(result.embeddable()).isFalse();
    }

    @Test
    void instagramIsLabeledButNeverEmbeddable() {
        EmbedResolution result = service.resolve("https://www.instagram.com/reel/abc123/");
        assertThat(result.platform()).isEqualTo(EmbedPlatform.INSTAGRAM);
        assertThat(result.embeddable()).isFalse();
    }

    @Test
    void unrecognizedDomainReturnsNotRecognized() {
        EmbedResolution result = service.resolve("https://example.com/some-page");
        assertThat(result.platform()).isNull();
        assertThat(result.embeddable()).isFalse();
    }

    @Test
    void malformedUrlReturnsNotRecognized() {
        EmbedResolution result = service.resolve("not a url at all");
        assertThat(result.platform()).isNull();
    }

    @Test
    void hostNormalizationStripsWwwAndMobilePrefix() {
        EmbedResolution result = service.resolve("https://m.twitch.tv/somechannel");
        assertThat(result.platform()).isEqualTo(EmbedPlatform.TWITCH);
    }
}
