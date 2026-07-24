.PHONY: all build dev test check docker-build docker-up docker-down clean help

# Default Target
all: build

## help: Display available Makefile targets
help:
	@echo "KoalaCast Developer Makefile"
	@echo ""
	@echo "Usage:"
	@echo "  make <target>"
	@echo ""
	@echo "Targets:"
	@echo "  build         Build Go API binary and SvelteKit web bundle"
	@echo "  dev-api       Start Go API backend in dev mode"
	@echo "  dev-web       Start SvelteKit web frontend in dev mode"
	@echo "  test          Run Go tests with race detection and Svelte typecheck"
	@echo "  check         Run svelte-check type checks on frontend"
	@echo "  docker-build  Build Docker container images using Compose"
	@echo "  docker-up     Launch Docker containers in background"
	@echo "  docker-down   Stop and remove running Docker containers"
	@echo "  clean         Remove build artifacts and temporary files"

## build: Build API binary & SvelteKit web bundle
build: build-api build-web

build-api:
	@echo "==> Building Go API binary..."
	@cd services/api && CGO_ENABLED=1 go build -ldflags="-w -s" -o koalacast-api ./cmd/server

build-web:
	@echo "==> Building SvelteKit production web application..."
	@cd apps/web && npm run build

## dev-api: Run Go API backend locally
dev-api:
	@cd services/api && SESSION_SECRET=a-very-secure-production-secret-with-at-least-32-characters go run ./cmd/server

## dev-web: Run SvelteKit frontend dev server
dev-web:
	@cd apps/web && npm run dev

## test: Run Go tests with race detector & svelte-check
test: test-api check

test-api:
	@echo "==> Running Go unit & integration tests with race detection..."
	@cd services/api && go test -race ./...

check:
	@echo "==> Running svelte-check type verification..."
	@cd apps/web && npm run check

## docker-build: Build Docker images
docker-build:
	@echo "==> Building Docker images..."
	@SESSION_SECRET=a-very-secure-production-secret-with-at-least-32-characters docker compose build

## docker-up: Start Docker containers
docker-up:
	@echo "==> Starting Docker environment..."
	@SESSION_SECRET=a-very-secure-production-secret-with-at-least-32-characters docker compose up -d

## docker-down: Stop Docker containers
docker-down:
	@echo "==> Stopping Docker environment..."
	@docker compose down

## clean: Clean build outputs and temporary databases
clean:
	@echo "==> Cleaning build artifacts..."
	@rm -rf apps/web/build apps/web/.svelte-kit
	@rm -f services/api/koalacast-api
	@rm -f services/api/*.db services/api/*.db-shm services/api/*.db-wal
