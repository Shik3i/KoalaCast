# syntax=docker/dockerfile:1

# Multi-arch note: every stage below builds on the *native* runner architecture
# and cross-compiles, rather than running under QEMU emulation. Emulation cost
# roughly 6x — the arm64 Go build alone took 12m55s against 2m05s native, and
# the arm64 web build 4m15s against 43s — which was the entire release time.

# Cross-compilation helpers (sets CC/GOARCH/sysroot per target platform).
# Pinned by digest: this image runs during the build, so it is part of the
# supply chain and must not float.
FROM --platform=$BUILDPLATFORM tonistiigi/xx:1.6.1@sha256:923441d7c25f1e2eb5789f82d987693c47b8ed987c4ab3b075d6ed2b5d6779a3 AS xx

# Stage 1: Build the SvelteKit web app (static SPA)
#
# Pinned to BUILDPLATFORM and built exactly once: the output is a bundle of
# static files with no architecture-specific content, so building it per target
# platform was pure waste.
FROM --platform=$BUILDPLATFORM node:24-alpine@sha256:d32cdf619f63fe0471182d08996dd516c6275bb5fd31ae06e55a570bd9e1ad43 AS builder-web
WORKDIR /app
RUN npm install --global npm@11.16.0
COPY apps/web/package.json apps/web/package-lock.json* ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY apps/web/ ./
RUN npm run build

# Stage 2: Build the single Go application binary
#
# Runs on the native architecture and cross-compiles to the target. CGO is
# required for the SQLite driver, so a target-matched C toolchain is installed
# via xx rather than emulating the whole build.
FROM --platform=$BUILDPLATFORM golang:1.26.5-alpine@sha256:0178a641fbb4858c5f1b48e34bdaabe0350a330a1b1149aabd498d0699ff5fb2 AS builder-api
COPY --from=xx / /
RUN apk add --no-cache clang lld
ARG TARGETPLATFORM
RUN xx-apk add --no-cache gcc musl-dev

WORKDIR /app
COPY services/api/go.mod services/api/go.sum* ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY services/api/ ./
# Build cache is keyed per target arch so amd64 and arm64 don't evict each other.
RUN --mount=type=cache,target=/root/.cache/go-build,id=go-build-$TARGETPLATFORM \
    --mount=type=cache,target=/go/pkg/mod \
    CGO_ENABLED=1 xx-go build -trimpath -ldflags="-w -s" -o koalacast ./cmd/server && \
    xx-verify koalacast

# Stage 3: Minimal runtime image
FROM alpine:3.24@sha256:28bd5fe8b56d1bd048e5babf5b10710ebe0bae67db86916198a6eec434943f8b AS runner
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
