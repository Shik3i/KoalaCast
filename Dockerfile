# syntax=docker/dockerfile:1

# Stage 1: Build the SvelteKit web app (static SPA)
FROM node:20-alpine AS builder-web
WORKDIR /app
COPY apps/web/package.json apps/web/package-lock.json* ./
RUN npm ci
COPY apps/web/ ./
RUN npm run build

# Stage 2: Build the single Go application binary
FROM golang:1.25-alpine AS builder-api
RUN apk add --no-cache gcc musl-dev
WORKDIR /app
COPY services/api/go.mod services/api/go.sum* ./
RUN go mod download
COPY services/api/ ./
# CGO is required for the SQLite driver. Trim the binary; the build-cache mount
# keeps incremental CI builds fast.
RUN --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=1 GOOS=linux go build -trimpath -ldflags="-w -s" -o koalacast ./cmd/server

# Stage 3: Minimal runtime image
FROM alpine:3.21 AS runner
RUN apk add --no-cache ca-certificates sqlite-libs tzdata wget

WORKDIR /app
RUN addgroup -S koala && adduser -S koala -G koala

# Application binary + compiled SPA assets
COPY --from=builder-api /app/koalacast /app/koalacast
COPY --from=builder-web /app/build /app/web/build

RUN mkdir -p /app/data && chown -R koala:koala /app

USER koala
EXPOSE 3000

ENV PORT=3000 \
    NODE_ENV=production \
    DATABASE_PATH=/app/data/koalacast.db \
    WEB_STATIC_DIR=/app/web/build

# Container-native liveness probe against the health endpoint.
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -q -O - http://localhost:3000/healthz >/dev/null 2>&1 || exit 1

CMD ["/app/koalacast"]
