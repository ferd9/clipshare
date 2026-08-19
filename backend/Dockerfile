FROM eclipse-temurin:21-jdk

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
