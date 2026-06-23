#!/usr/bin/env bash
set -euo pipefail

AWS_LOCAL="aws --output json --region eu-north-1"
SNS="$AWS_LOCAL --endpoint-url=http://localhost:4566 sns"
SQS="$AWS_LOCAL --endpoint-url=http://localhost:4566 sqs"
KMS="$AWS_LOCAL --endpoint-url=http://localhost:4566 kms"
DDB="$AWS_LOCAL --endpoint-url=http://localhost:8000 dynamodb"

# ---------------------------------------------------------------------------
# SNS topics (create-topic is idempotent in LocalStack)
# ---------------------------------------------------------------------------
for topic in payment-events checkout-events; do
  $SNS create-topic --name "$topic" >/dev/null
  echo "  SNS topic: $topic"
done

# ---------------------------------------------------------------------------
# SQS queues with DLQs
# ---------------------------------------------------------------------------
create_queue_with_dlq() {
  local queue="$1"
  local dlq_url dlq_arn

  dlq_url=$($SQS create-queue --queue-name "${queue}-dlq" | jq -r .QueueUrl)
  dlq_arn=$($SQS get-queue-attributes --queue-url "$dlq_url" \
              --attribute-names QueueArn | jq -r .Attributes.QueueArn)

  $SQS create-queue --queue-name "$queue" \
    --attributes "{\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$dlq_arn\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"}" >/dev/null

  echo "  SQS queue: $queue (DLQ: ${queue}-dlq)"
}

create_queue_with_dlq webhook-dispatch
create_queue_with_dlq payment-reconciliation

# ---------------------------------------------------------------------------
# SNS → SQS subscription (idempotent: skip if webhook-dispatch already subscribed)
# ---------------------------------------------------------------------------
PAY_TOPIC_ARN=$($SNS list-topics | jq -r '.Topics[] | select(.TopicArn|endswith(":payment-events")) | .TopicArn')
WEBHOOK_Q_URL=$($SQS get-queue-url --queue-name webhook-dispatch | jq -r .QueueUrl)
WEBHOOK_Q_ARN=$($SQS get-queue-attributes --queue-url "$WEBHOOK_Q_URL" \
                  --attribute-names QueueArn | jq -r .Attributes.QueueArn)

ALREADY_SUBSCRIBED=$($SNS list-subscriptions-by-topic --topic-arn "$PAY_TOPIC_ARN" \
  | jq -r ".Subscriptions[] | select(.Endpoint == \"$WEBHOOK_Q_ARN\") | .SubscriptionArn" || true)

if [ -z "$ALREADY_SUBSCRIBED" ]; then
  $SNS subscribe --topic-arn "$PAY_TOPIC_ARN" --protocol sqs \
    --notification-endpoint "$WEBHOOK_Q_ARN" >/dev/null
  echo "  SNS subscription: payment-events → webhook-dispatch"
else
  echo "  SNS subscription: payment-events → webhook-dispatch (already exists)"
fi

# ---------------------------------------------------------------------------
# KMS key + alias (idempotent: skip alias creation if key already resolves)
# ---------------------------------------------------------------------------
KEY_EXISTS=0
$KMS describe-key --key-id alias/token-service-dev >/dev/null 2>&1 && KEY_EXISTS=1 || true

if [ "$KEY_EXISTS" -eq 0 ]; then
  KEY_ID=$($KMS create-key --description "token-service envelope encryption (dev)" \
    | jq -r .KeyMetadata.KeyId)
  $KMS create-alias --alias-name alias/token-service-dev --target-key-id "$KEY_ID" >/dev/null
  echo "  KMS key: alias/token-service-dev ($KEY_ID)"
else
  echo "  KMS key: alias/token-service-dev (already exists)"
fi

# ---------------------------------------------------------------------------
# DynamoDB tables (each guarded by describe-table; skip if exists)
# ---------------------------------------------------------------------------
create_dynamo_table() {
  local table_name="$1"
  shift
  if $DDB describe-table --table-name "$table_name" >/dev/null 2>&1; then
    echo "  DynamoDB table: $table_name (already exists)"
    return
  fi
  $DDB create-table --table-name "$table_name" "$@" \
    --billing-mode PAY_PER_REQUEST >/dev/null
  echo "  DynamoDB table: $table_name (created)"
}

enable_ttl() {
  local table_name="$1"
  local attr="$2"
  $DDB update-time-to-live --table-name "$table_name" \
    --time-to-live-specification "Enabled=true, AttributeName=$attr" >/dev/null 2>&1 || true
}

# checkout_sessions: HASH session_id, GSI merchant-created-index
create_dynamo_table checkout_sessions \
  --attribute-definitions \
    AttributeName=session_id,AttributeType=S \
    AttributeName=merchant_id,AttributeType=S \
    AttributeName=created_at,AttributeType=N \
  --key-schema AttributeName=session_id,KeyType=HASH \
  --global-secondary-indexes \
    "[{\"IndexName\":\"merchant-created-index\",
       \"KeySchema\":[{\"AttributeName\":\"merchant_id\",\"KeyType\":\"HASH\"},
                      {\"AttributeName\":\"created_at\",\"KeyType\":\"RANGE\"}],
       \"Projection\":{\"ProjectionType\":\"ALL\"}}]"
enable_ttl checkout_sessions expires_at

# tokens: HASH token
create_dynamo_table tokens \
  --attribute-definitions AttributeName=token,AttributeType=S \
  --key-schema AttributeName=token,KeyType=HASH
enable_ttl tokens expires_at

# data_keys: HASH data_key_id (encrypted DEKs for token-service envelope encryption)
create_dynamo_table data_keys \
  --attribute-definitions AttributeName=data_key_id,AttributeType=S \
  --key-schema AttributeName=data_key_id,KeyType=HASH
enable_ttl data_keys expires_at

# token_rate_limits: per-session attempt counter for BIN-scraping prevention (task 2.4)
create_dynamo_table token_rate_limits \
  --attribute-definitions AttributeName=session_id,AttributeType=S \
  --key-schema AttributeName=session_id,KeyType=HASH
enable_ttl token_rate_limits expires_at

# idempotency key tables (one per service that exposes POST endpoints)
for svc in checkout payment; do
  create_dynamo_table "${svc}_idempotency_keys" \
    --attribute-definitions AttributeName=idempotency_key,AttributeType=S \
    --key-schema AttributeName=idempotency_key,KeyType=HASH
  enable_ttl "${svc}_idempotency_keys" expires_at
done

echo ""
echo "Bootstrap complete."
