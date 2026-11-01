# Stage 1: Build application using Maven wrapper
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Install GNU wget & curl for reliable HTTPS downloads in Alpine
RUN apk add --no-cache wget curl

# Copy Maven wrapper and descriptor files
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw

# Copy application sources
COPY src ./src

# Build JAR with BuildKit cache for Maven dependencies
RUN --mount=type=cache,target=/root/.m2 ./mvnw clean package -DskipTests

# Stage 2: Runtime image
FROM eclipse-temurin:21-jre-alpine AS runner
WORKDIR /app

# Create non-root user
RUN addgroup -S techhouse && adduser -S techhouse -G techhouse
USER techhouse

COPY --from=builder /app/target/techhouse-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
