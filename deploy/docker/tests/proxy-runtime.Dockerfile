# Offline runtime contract for the public backend. Example:
# docker buildx build --network none --output type=cacheonly --build-arg BACKEND_IMAGE=minjm/codex-quota-guard-backend:0.3.23 -f deploy/docker/tests/proxy-runtime.Dockerfile .
ARG BACKEND_IMAGE=minjm/codex-quota-guard-backend:0.3.23
FROM ${BACKEND_IMAGE}
# Inherit the published runtime user; do not make the check pass by using root.
RUN test "$(id -u)" = 10001 \
    && sing-box version \
    && printf '%s\n' '{"outbounds":[{"type":"direct","tag":"upstream"}],"route":{"final":"upstream"}}' \
       | sing-box check -c stdin
