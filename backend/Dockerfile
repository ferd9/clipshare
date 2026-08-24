FROM eclipse-temurin:21-jdk

# ffmpeg + yt-dlp: el backend ahora hace síncrono (en el propio request) el chequeo de
# duración de un link externo antes de encolar la descarga completa (que sí hace el worker,
# async), y también sube/procesa audio de reemplazo (subida propia o link) — corto, entra
# cómodo en el tiempo de una request HTTP normal. Mismo patrón que Dockerfile.worker.
RUN sed -i 's|http://archive.ubuntu.com|https://archive.ubuntu.com|g; s|http://security.ubuntu.com|https://security.ubuntu.com|g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get update && apt-get install -y ffmpeg curl && rm -rf /var/lib/apt/lists/* \
    && curl -L https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp -o /usr/local/bin/yt-dlp \
    && chmod a+rx /usr/local/bin/yt-dlp

WORKDIR /app

# Cachear dependencias por separado del código fuente
COPY .mvn/ .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src ./src

EXPOSE 8080

# Modo dev: recompila y corre con Spring Boot. El código real llega vía bind mount
# (ver docker-compose.yml), así que este build solo deja el entorno listo.
CMD ["./mvnw", "-B", "spring-boot:run"]
