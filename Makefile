.PHONY: help up down build test full frontend-install frontend-build frontend-test frontend-check-ui frontend-check-bundle docs-check docs-regenerate docs-verify

PY ?= python3

help:
	@echo "NoeWise Java Development"
	@echo "==========================="
	@echo "make up           - Start postgres + redis + nacos (Java compose)"
	@echo "make down         - Stop Java compose containers"
	@echo "make build        - Build Java backend with Gradle"
	@echo "make test         - Run Java backend tests"
	@echo "make full         - Start full Java stack (services + gateway)"
	@echo "make frontend-install - Install the pnpm frontend workspace from the root lockfile"
	@echo "make frontend-build   - Build all frontend applications through pnpm filters"
	@echo "make frontend-test    - Run all frontend test suites through pnpm filters"
	@echo "make frontend-check-ui - Enforce UI migration rules"
	@echo "make frontend-check-bundle - Check existing frontend entry gzip budgets"
	@echo "make docs-check   - Verify links/structure/freshness and report advisory length statistics"
	@echo "make docs-regenerate - Regenerate generated docs (module graph)"
	@echo "make docs-verify  - Regenerate then diff-check freshness (CI double gate)"

up:
	docker compose -f deploy/docker-compose.java.yml up -d postgres redis nacos

down:
	docker compose -f deploy/docker-compose.java.yml down

build:
	cd backend-java && ./gradlew build --no-daemon

test:
	cd backend-java && ./gradlew test --no-daemon

full:
	docker compose -f deploy/docker-compose.java.yml up -d

frontend-install:
	corepack enable
	pnpm install --frozen-lockfile

frontend-build:
	pnpm --filter frontend-admin build
	pnpm --filter frontend-platform build
	pnpm --filter frontend-chat build

frontend-test:
	pnpm --filter frontend-admin test
	pnpm --filter frontend-platform test
	pnpm --filter frontend-chat test

frontend-check-ui:
	pnpm --filter frontend-admin check:ui

frontend-check-bundle:
	pnpm --filter frontend-admin check:bundle
	pnpm --filter frontend-platform check:bundle

docs-check:
	$(PY) tools/docs/check.py

docs-regenerate:
	$(PY) tools/docs/gen_module_graph.py

docs-verify: docs-regenerate
	git diff --exit-code -- docs/08-数据与工具/generated/
