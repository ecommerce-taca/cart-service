# syntax=docker/dockerfile:1.7

# ---- Build stage ----
# Java 25 khớp <java.version> trong pom.xml. Test chạy ở CI trước khi build image.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace

# Copy pom trước để cache dependency khi chỉ đổi source.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

# ---- Runtime stage ----
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# User không có quyền root để chạy app
RUN groupadd --system spring && useradd --system --gid spring --no-create-home spring

COPY --from=build --chown=spring:spring /workspace/target/cart-service-0.0.1-SNAPSHOT.jar /app/app.jar

USER spring:spring
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
