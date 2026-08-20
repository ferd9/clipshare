package com.clipshare.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

/**
 * Sirve los archivos ya publicados (video/thumbnail) directo desde el filesystem local en
 * /media/clips/**. Solo aplica con STORAGE_MODE=local; en prod (S3/R2, ver docs/SPEC.md
 * sección 16) los clips se sirven desde la URL pública del bucket/CDN, no desde acá.
 *
 * Importante: esto mapea únicamente la carpeta "public/" (ver StorageService /
 * ClipProcessingWorker), nunca "raw/" ni "work/" — un clip solo se vuelve accesible por
 * esta ruta después de "pasar moderación" (sección 10), incluso si hoy esa moderación es
 * el mock que aprueba todo de la Fase 2.
 */
@Configuration
@ConditionalOnProperty(name = "app.storage.mode", havingValue = "local", matchIfMissing = true)
public class WebConfig implements WebMvcConfigurer {

    private final String localStoragePath;

    public WebConfig(@Value("${app.storage.local-path}") String localStoragePath) {
        this.localStoragePath = localStoragePath;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String base = localStoragePath.endsWith("/") ? localStoragePath : localStoragePath + "/";
        registry.addResourceHandler("/media/clips/**")
                .addResourceLocations("file:" + base + "public/")
                // Cache-Control público: el nombre de archivo es fijo (final.mp4/thumb.jpg) pero
                // el contenido de un clip nunca cambia una vez publicado, así que cachear fuerte
                // es seguro. Necesario además porque Spring Security desactivó el Cache-Control
                // por defecto acá (ver SecurityConfig) — sin ninguno, Chrome no reproduce el video.
                .setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic());

        // Adjuntos de imagen en comentarios (Fase 6b, docs/SPEC.md sección 11.9). A diferencia
        // de los clips, acá no hay una carpeta "public/" separada de "raw/": la verificación
        // CSAM corre síncrona al subir (ver CommentAttachmentService) — si el archivo llegó a
        // quedar en disco es porque ya está aprobado, no hace falta la misma separación de
        // etapas que en el pipeline de video.
        registry.addResourceHandler("/media/attachments/**")
                .addResourceLocations("file:" + base + "attachments/")
                .setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic());
    }
}
