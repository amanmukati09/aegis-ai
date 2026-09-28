# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Cache dependencies first
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
# Non-root user
RUN useradd -r -u 1001 aegis
COPY --from=build /build/target/gateway-*.jar app.jar
USER aegis
EXPOSE 8080
# Memory-tuned for a small host: cap heap to a % of the container limit.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60.0 -XX:+UseSerialGC"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
