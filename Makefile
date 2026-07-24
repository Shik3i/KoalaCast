# KoalaCast Developer Makefile
.DEFAULT_GOAL := help
.PHONY: all build build-api build-web dev-api dev-web test test-api check \
        fmt vet tidy docker-build docker-up docker-down clean help

# Dev-only session secret. Override for local testing: make dev-api SESSION_SECRET=...
# Never used for real deployments — production supplies its own via the environment.
SESSION_SECRET ?= a-very-secure-development-secret-with-at-least-32-characters

all: build

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
	@cd services/api && SESSION_SECRET=$(SESSION_SECRET) go run ./cmd/server

## dev-web: Run SvelteKit frontend dev server
dev-web:
	@cd apps/web && npm run dev

## test: Run Go tests with race detector & svelte-check
test: test-api check

test-api:
	@echo "==> Running Go unit & integration tests with race detection..."
	@cd services/api && go test -race ./...

## check: Run svelte-check type verification
check:
	@echo "==> Running svelte-check type verification..."
	@cd apps/web && npm run check

## fmt: Format Go sources
fmt:
	@echo "==> Formatting Go sources..."
	@cd services/api && gofmt -w .

## vet: Run go vet static analysis
vet:
	@echo "==> Running go vet..."
	@cd services/api && go vet ./...

## tidy: Sync Go module dependencies
tidy:
	@cd services/api && go mod tidy

## docker-build: Build Docker images via Compose
docker-build:
	@echo "==> Building Docker images..."
	@SESSION_SECRET=$(SESSION_SECRET) docker compose build

## docker-up: Start Docker containers
docker-up:
	@echo "==> Starting Docker environment..."
	@SESSION_SECRET=$(SESSION_SECRET) docker compose up -d

## docker-down: Stop Docker containers
docker-down:
	@echo "==> Stopping Docker environment..."
	@docker compose down

## clean: Remove build artifacts, coverage & temporary databases
clean:
	@echo "==> Cleaning build artifacts..."
	@rm -rf apps/web/build apps/web/.svelte-kit
	@rm -f services/api/koalacast-api services/api/cmd/server/server
	@rm -f services/api/coverage.out services/api/coverage.html services/api/coverage.txt
	@rm -f services/api/*.db services/api/*.db-shm services/api/*.db-wal

## help: Display available Makefile targets
help:
	@echo "KoalaCast Developer Makefile"
	@echo ""
	@echo "Usage: make <target>"
	@echo ""
	@echo "Targets:"
	@grep -hE '^## [a-zA-Z_-]+:' $(MAKEFILE_LIST) | sed 's/## //' | \
		awk -F': ' '{ printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2 }'
