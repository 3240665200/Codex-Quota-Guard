FROM alpine:3.22 AS proxy-core
ARG TARGETARCH
# The current server distribution is linux/amd64. Fail rather than bundling an
# executable for the wrong architecture. Unmodified upstream binary + license.
RUN test "$TARGETARCH" = "amd64" \
    && apk add --no-cache ca-certificates curl tar \
    && curl --fail --location --proto '=https' --max-time 180 \
       https://github.com/SagerNet/sing-box/releases/download/v1.13.14/sing-box-1.13.14-linux-amd64.tar.gz \
       --output /tmp/sing-box.tar.gz \
    && echo 'f48703461a15476951ac4967cdad339d986f4b8096b4eb3ff0829a500502d697  /tmp/sing-box.tar.gz' | sha256sum -c - \
    && tar -xzf /tmp/sing-box.tar.gz -C /tmp \
    && cp /tmp/sing-box-1.13.14-linux-amd64/sing-box /usr/local/bin/sing-box \
    && mkdir -p /usr/share/licenses/sing-box \
    && cp /tmp/sing-box-1.13.14-linux-amd64/LICENSE /usr/share/licenses/sing-box/LICENSE

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
COPY --from=proxy-core /usr/local/bin/sing-box /usr/local/bin/sing-box
COPY --from=proxy-core /usr/share/licenses/sing-box /usr/share/licenses/sing-box
ENV MANAGED_PROXY_SING_BOX_PATH=/usr/local/bin/sing-box
COPY --from=build --chown=10001:10001 /build/target/web-backend-0.1.0-SNAPSHOT.jar ./server.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/server.jar"]

# Opt-in build target for the server-owned, temporary login browser. Keep
# Chromium's sandbox enabled; the matching Compose overlay permits user namespaces.
FROM runtime AS browser-runtime
USER root
RUN apk add --no-cache chromium font-noto-cjk
ENV WEB_LOGIN_BROWSER_EXECUTABLE=/usr/lib/chromium/chrome
USER 10001:10001
