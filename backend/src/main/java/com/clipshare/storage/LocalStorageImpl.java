package com.clipshare.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
@ConditionalOnProperty(name = "app.storage.mode", havingValue = "local", matchIfMissing = true)
public class LocalStorageImpl implements StorageService {

    private final Path basePath;

    public LocalStorageImpl(@Value("${app.storage.local-path}") String basePath) {
        this.basePath = Path.of(basePath);
    }

    @Override
    public String store(String relativePath, InputStream content) throws IOException {
        Path target = resolveLocalPath(relativePath);
        Files.createDirectories(target.getParent());
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        return relativePath;
    }

    @Override
    public Path resolveLocalPath(String relativePath) {
        // clipId siempre lo genera el servidor (UUID), nunca viene de input de usuario,
        // pero igual normalizamos para no depender de esa garantía en el futuro.
        Path resolved = basePath.resolve(relativePath).normalize();
        if (!resolved.startsWith(basePath)) {
            throw new IllegalArgumentException("Ruta de storage inválida: " + relativePath);
        }
        return resolved;
    }

    @Override
    public void delete(String relativePath) throws IOException {
        Files.deleteIfExists(resolveLocalPath(relativePath));
    }
}
