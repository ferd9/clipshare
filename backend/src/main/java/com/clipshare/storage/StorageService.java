package com.clipshare.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Interfaz reemplazable: en local (`STORAGE_MODE=local`) es filesystem plano; en prod
 * (`STORAGE_MODE=s3`) es Cloudflare R2 (ver docs/SPEC.md secciones 3 y 16). El worker de
 * ffmpeg necesita rutas de filesystem reales (no streams) para invocar el binario, por eso
 * la interfaz expone {@link #resolveLocalPath} además de guardar/leer bytes.
 */
public interface StorageService {

    /** Guarda el contenido bajo una ruta relativa (ej. "{clipId}/original.mp4") y la devuelve. */
    String store(String relativePath, InputStream content) throws IOException;

    /** Resuelve una ruta relativa a un Path de filesystem real, para que ffmpeg pueda leerlo/escribirlo directo. */
    Path resolveLocalPath(String relativePath);

    void delete(String relativePath) throws IOException;
}
