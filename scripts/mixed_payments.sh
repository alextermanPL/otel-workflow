#!/bin/bash
# Usage:
#   ./mixed_payments.sh [start] [end]
#
# For each payment ID in [start..end]:
#   - initiates the payment immediately
#   - randomly picks success or failure for the reservation signal
#   - sends the signal after a log-uniform random delay between 80ms and 80min
#   - signals run in background so all payments are kicked off first,
#     then signals trickle in naturally across the delay window
#
# Examples:
#   ./mixed_payments.sh 1 10
#   ./mixed_payments.sh 50 60

START=${1:-1}
END=${2:-$START}

# Kill the entire process group on Ctrl+C or termination.
# This takes down all background signal jobs cleanly.
trap 'echo ""; echo "Caught signal — killing all background jobs..."; kill 0; exit 1' INT TERM

MIN_S=0.08   # 80 ms
MAX_S=1500   # 25 minutes

# Log-uniform distribution: equal probability per order of magnitude
# (ms range, seconds range, minutes range all equally likely)
random_delay_s() {
  awk -v seed="$RANDOM$RANDOM" -v min="$MIN_S" -v max="$MAX_S" \
    'BEGIN { srand(seed+0); printf "%.3f", exp(log(min) + rand() * (log(max) - log(min))) }'
}

human_delay() {
  awk -v s="$1" 'BEGIN {
    if (s < 60) { printf "%.1fs", s }
    else         { printf "%dm %ds", int(s/60), int(s) % 60 }
  }'
}

signal_payment() {
  local ID=$1 REQUEST_ID=$2 DELAY=$3 SUCCESS=$4
  local BODY MODE

  if [ "$SUCCESS" = "true" ]; then
    BODY='{"success": true}'
    MODE="success"
  else
    BODY='{"success": false, "reason": "insufficient funds"}'
    MODE="fail"
  fi

  sleep "$DELAY"
  curl -s -X POST "http://localhost:9090/payments/$ID/reservation-result" \
    -H "Content-Type: application/json" \
    -H "x-request-id: $REQUEST_ID" \
    -d "$BODY" > /dev/null
  echo "  [payment:$ID] signal sent  mode=$MODE  delay=$(human_delay "$DELAY")"
}

for ID in $(seq $START $END); do
  REQUEST_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
  DELAY=$(random_delay_s)
  DELAY_HUMAN=$(human_delay "$DELAY")
  SUCCESS=$([ $((RANDOM % 2)) -eq 0 ] && echo "true" || echo "false")
  MODE=$([ "$SUCCESS" = "true" ] && echo "success" || echo "fail")

  echo "------------------------------------------"
  echo "Payment ID: $ID  mode=$MODE  signal_in=$DELAY_HUMAN  x-request-id=$REQUEST_ID"
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

  echo "==> Step 2: Signal scheduled in $DELAY_HUMAN (background)"
  signal_payment "$ID" "$REQUEST_ID" "$DELAY" "$SUCCESS" &

  echo ""
done

echo "========================================"
echo "All $((END - START + 1)) payment(s) initiated."
echo "Signals pending in background — waiting for all to fire..."
echo "Max possible wait: ~$(human_delay "$MAX_S")"
echo "========================================"
wait
echo "Done. All signals delivered."
