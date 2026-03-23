#!/bin/bash

# Default to 1 if no arguments are provided
START=${1:-1}
END=${2:-$START}

for ID in $(seq $START $END); do
  echo "------------------------------------------"
  echo "Processing Payment ID: $ID"
  echo "------------------------------------------"

  echo "==> Step 1: Initiate payment"
  curl -s -X POST http://localhost:9090/payments \
    -H "Content-Type: application/json" \
    -d "{
      \"paymentId\": \"$ID\",
      \"amount\": 100.00,
      \"currency\": \"EUR\",
      \"debtorAccount\": \"LT001\",
      \"creditorAccount\": \"LT002\"
    }" | jq .

  echo ""
  echo "==> Step 2: Send reservation signal (bank callback)"
  curl -s -X POST "http://localhost:9090/payments/$ID/reservation-result" \
    -H "Content-Type: application/json" \
    -d '{"success": true}' | jq .

  echo -e "\n"
done