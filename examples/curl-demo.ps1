$ErrorActionPreference = "Stop"
$INV = if ($env:INV) { $env:INV } else { "http://localhost:8081" }
$ORD = if ($env:ORD) { $env:ORD } else { "http://localhost:8080" }

Write-Host "== Create product =="
$product = Invoke-RestMethod -Method Post -Uri "$INV/api/products" `
  -Headers @{ "X-Correlation-Id" = "curl-demo-1" } `
  -ContentType "application/json" `
  -Body '{"name":"Filter Coffee","price":18.00,"availableQuantity":5}'
$product | ConvertTo-Json -Compress
$productId = $product.id

Write-Host "== Place order =="
$orderBody = @{
  customerEmail = "buyer@example.com"
  items = @(@{ productId = $productId; quantity = 2 })
} | ConvertTo-Json -Depth 5

$order = Invoke-RestMethod -Method Post -Uri "$ORD/api/orders" `
  -Headers @{
    "Idempotency-Key" = "curl-order-1"
    "X-Correlation-Id" = "curl-demo-2"
  } `
  -ContentType "application/json" `
  -Body $orderBody
$order | ConvertTo-Json -Depth 5

Write-Host "== Get order =="
Invoke-RestMethod -Uri "$ORD/api/orders/$($order.id)" `
  -Headers @{ "X-Correlation-Id" = "curl-demo-3" } | ConvertTo-Json -Depth 5
