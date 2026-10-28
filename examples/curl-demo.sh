#!/usr/bin/env bash
set -euo pipefail

INV=${INV:-http://localhost:8081}
ORD=${ORD:-http://localhost:8080}

echo "== Create product =="
PRODUCT=$(curl -sS -X POST "$INV/api/products" \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: curl-demo-1" \
  -d '{"name":"Filter Coffee","price":18.00,"availableQuantity":5}')
echo "$PRODUCT"
PRODUCT_ID=$(echo "$PRODUCT" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

echo "== Place order =="
ORDER=$(curl -sS -X POST "$ORD/api/orders" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: curl-order-1" \
  -H "X-Correlation-Id: curl-demo-2" \
  -d "{\"customerEmail\":\"buyer@example.com\",\"items\":[{\"productId\":\"$PRODUCT_ID\",\"quantity\":2}]}")
echo "$ORDER"
ORDER_ID=$(echo "$ORDER" | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

echo "== Get order =="
curl -sS "$ORD/api/orders/$ORDER_ID" -H "X-Correlation-Id: curl-demo-3"
echo
