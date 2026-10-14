FROM node:22-bookworm-slim AS build
WORKDIR /build
ENV CI=true
RUN npm install --global pnpm@10.12.3
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY apps/admin/frontend/package.json ./apps/admin/frontend/package.json
# Include the other workspace manifest so the frozen lockfile has all importers.
COPY apps/client/frontend/package.json ./apps/client/frontend/package.json
RUN pnpm --filter codex-quota-guard-admin install --frozen-lockfile --ignore-scripts
COPY apps/admin/frontend ./apps/admin/frontend
RUN pnpm --dir apps/admin/frontend exec max setup \
    && pnpm --dir apps/admin/frontend build

FROM nginx:1.30-alpine AS runtime
COPY deploy/docker/nginx.conf /etc/nginx/nginx.conf
COPY --from=build /build/apps/admin/frontend/dist /usr/share/nginx/html
USER nginx
EXPOSE 8080
# Skip the image's root-oriented entrypoint scripts; the config is already complete.
ENTRYPOINT ["nginx"]
CMD ["-g", "daemon off;"]
