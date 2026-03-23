#!/bin/bash
# Usage:
#   ./un_success_payment.sh [start] [end] [reason]
#
# Examples:
#   ./un_success_payment.sh 1 5
#   ./un_success_payment.sh 6 6 "account frozen"

START=${1:-1}
END=${2:-$START}
REASON=${3:-insufficient funds}

SIGNAL_BODY="{\"success\": false, \"reason\": \"$REASON\"}"

for ID in $(seq $START $END); do
  REQUEST_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"

  echo "------------------------------------------"
  echo "Processing Payment ID: $ID  mode=fail  x-request-id=$REQUEST_ID"
  echo "------------------------------------------"

  echo "==> Step 1: Initiate payment"
  curl -s -X POST http://localhost:9090/payments \
    -H "Content-Type: application/json" \
    -H "x-request-id: $REQUEST_ID" \
    -d "{
      \"paymentId\": \"$ID\",
      \"amount\": 100.00,
      \"currency\": \"EUR\",
      \"debtorAccount\": \"LT001\",
      \"creditorAccount\": \"LT002\"
    }" | jq .

  echo ""
  echo "==> Step 2: Send reservation signal"
  curl -s -X POST "http://localhost:9090/payments/$ID/reservation-result" \
    -H "Content-Type: application/json" \
    -H "x-request-id: $REQUEST_ID" \
    -d "$SIGNAL_BODY" | jq .

  echo -e "\n"
done
