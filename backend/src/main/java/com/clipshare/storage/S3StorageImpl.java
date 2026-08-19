package com.clipshare.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

// TODO: integrar Cloudflare R2 (S3-compatible) antes de producción — ver docs/SPEC.md
// secciones 3, 13 y 16 (S3_ENDPOINT/S3_BUCKET/S3_ACCESS_KEY/S3_SECRET_KEY). Queda
// registrado como bean reemplazable (STORAGE_MODE=s3) igual que las demás integraciones
// pendientes de trámite/credenciales (CsamHashService, NcmecReportClient, EmailService).
@Service
@ConditionalOnProperty(name = "app.storage.mode", havingValue = "s3")
public class S3StorageImpl implements StorageService {

    @Override
    public String store(String relativePath, InputStream content) throws IOException {
        throw new UnsupportedOperationException("STORAGE_MODE=s3 todavía no está implementado");
    }

    @Override
    public Path resolveLocalPath(String relativePath) {
        throw new UnsupportedOperationException("STORAGE_MODE=s3 no expone rutas de filesystem local");
    }

    @Override
    public void delete(String relativePath) throws IOException {
        throw new UnsupportedOperationException("STORAGE_MODE=s3 todavía no está implementado");
    }
}
