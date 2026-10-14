FROM maven:3.10.0-eclipse-temurin-17-alpine AS build
WORKDIR /build
COPY apps/admin/backend/pom.xml ./pom.xml
RUN mvn -B -ntp dependency:go-offline
COPY apps/admin/backend/src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre-alpine AS runtime
RUN apk add --no-cache curl \
    && addgroup -S -g 10001 quota \
    && adduser -S -D -H -u 10001 -G quota quota
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/web-backend-0.1.0-SNAPSHOT.jar ./server.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
