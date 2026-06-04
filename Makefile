.PHONY: dev-up dev-up-full dev-down dev-bootstrap dev-reset dev-logs wait-for-ready build format

COMPOSE := docker compose -f infrastructure/docker-compose.yml

dev-up:
	$(COMPOSE) up -d

dev-up-full:
	$(COMPOSE) --profile full up -d

dev-down:
	$(COMPOSE) down -v

# Poll until LocalStack SNS/SQS/KMS are available and DynamoDB Local responds.
# LocalStack 4.x reports "available" (not "running") for enabled services.
wait-for-ready:
	@echo "Waiting for LocalStack..."
	@bash -c '\
	  for i in $$(seq 1 30); do \
	    STATUS=$$(curl -fsS http://localhost:4566/_localstack/health 2>/dev/null \
	      | python3 -c "import sys,json; h=json.load(sys.stdin); print(h[\"services\"].get(\"sns\",\"off\"))" 2>/dev/null); \
	    if [ "$$STATUS" = "available" ] || [ "$$STATUS" = "running" ]; then \
	      echo "LocalStack ready (sns: $$STATUS)."; break; \
	    fi; \
	    echo "  ...$$((i*2))s (sns: $$STATUS)"; \
	    if [ $$i -eq 30 ]; then echo "ERROR: LocalStack timed out"; exit 1; fi; \
	    sleep 2; \
	  done'
	@echo "Waiting for DynamoDB Local..."
	@bash -c '\
	  for i in $$(seq 1 15); do \
	    if aws --region eu-north-1 --endpoint-url=http://localhost:8000 dynamodb list-tables --output json >/dev/null 2>&1; then \
	      echo "DynamoDB Local ready."; break; \
	    fi; \
	    echo "  ...$$((i*2))s"; \
	    if [ $$i -eq 15 ]; then echo "ERROR: DynamoDB Local timed out"; exit 1; fi; \
	    sleep 2; \
	  done'
	@echo "Infrastructure ready."

dev-bootstrap: wait-for-ready
	./infrastructure/scripts/bootstrap-aws.sh

# dev-seed is added in task 1.3 once merchant-service exists.
dev-reset: dev-down dev-up dev-bootstrap

dev-logs:
	$(COMPOSE) logs -f

build:
	./mvnw clean verify

format:
	./mvnw spotless:apply
	cd frontend/checkout-ui && pnpm format
