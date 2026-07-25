# Stage 1: Build SvelteKit Web Application (Static SPA)
FROM node:20-alpine AS builder-web
WORKDIR /app
COPY apps/web/package.json apps/web/package-lock.json* ./
RUN npm ci
COPY apps/web/ ./
RUN npm run build

# Stage 2: Build Go Single Application Binary
FROM golang:alpine AS builder-api
RUN apk add --no-cache gcc musl-dev
WORKDIR /app
COPY services/api/go.mod services/api/go.sum* ./
RUN go mod download
COPY services/api/ ./
RUN CGO_ENABLED=1 GOOS=linux go build -ldflags="-w -s" -o koalacast ./cmd/server

# Stage 3: Minimal Single Application Container
FROM alpine:latest AS runner

RUN apk add --no-cache ca-certificates sqlite-libs tzdata

WORKDIR /app
RUN addgroup -S koala && adduser -S koala -G koala

# Copy Go single binary executable
COPY --from=builder-api /app/koalacast /app/koalacast

# Copy compiled SvelteKit web static assets
COPY --from=builder-web /app/build /app/web/build

# Data directory permissions
RUN mkdir -p /app/data && chown -R koala:koala /app

USER koala

EXPOSE 3000

ENV PORT=3000
ENV NODE_ENV=production
ENV DATABASE_PATH=/app/data/koalacast.db
ENV WEB_STATIC_DIR=/app/web/build

CMD ["/app/koalacast"]
