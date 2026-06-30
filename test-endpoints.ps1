Write-Host "`n╔════════════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║        ECOMMERCE PLATFORM - COMPREHENSIVE ENDPOINT TEST       ║" -ForegroundColor Cyan
Write-Host "╚════════════════════════════════════════════════════════════════╝`n" -ForegroundColor Cyan

$services = @(
    @{name="user-service"; port=8081},
    @{name="product-service"; port=8082},
    @{name="order-service"; port=8083},
    @{name="inventory-service"; port=8084},
    @{name="payment-service"; port=8085},
    @{name="notification-service"; port=8086}
)

$successCount = 0
$failCount = 0

foreach ($service in $services) {
    Write-Host "┌─ $($service.name) (PORT: $($service.port)) " -ForegroundColor Yellow -NoNewline
    Write-Host ("─" * (50 - $service.name.Length - 11)) -ForegroundColor Yellow
    
    $baseUrl = "http://localhost:$($service.port)"
    
    # Health Check
    try {
        $response = Invoke-WebRequest -Uri "$baseUrl/actuator/health" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
        $data = $response.Content | ConvertFrom-Json
        Write-Host "  ✓ /actuator/health" -ForegroundColor Green -NoNewline
        Write-Host " → Status: $($data.status)" -ForegroundColor Green
        $successCount++
    }
    catch {
        Write-Host "  ✗ /actuator/health → FAILED" -ForegroundColor Red
        $failCount++
    }
    
    # Info Endpoint
    try {
        $response = Invoke-WebRequest -Uri "$baseUrl/actuator/info" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
        Write-Host "  ✓ /actuator/info" -ForegroundColor Green -NoNewline
        Write-Host " → Available" -ForegroundColor Green
        $successCount++
    }
    catch {
        Write-Host "  ✗ /actuator/info → FAILED" -ForegroundColor Red
        $failCount++
    }
    
    # Metrics Endpoint
    try {
        $response = Invoke-WebRequest -Uri "$baseUrl/actuator/metrics" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
        Write-Host "  ✓ /actuator/metrics" -ForegroundColor Green -NoNewline
        Write-Host " → Available" -ForegroundColor Green
        $successCount++
    }
    catch {
        Write-Host "  ✗ /actuator/metrics → FAILED" -ForegroundColor Red
        $failCount++
    }
    
    Write-Host ""
}

Write-Host "`n╔════════════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║                      INFRASTRUCTURE TESTS                      ║" -ForegroundColor Cyan
Write-Host "╚════════════════════════════════════════════════════════════════╝`n" -ForegroundColor Cyan

# Kong API Gateway
try {
    $response = Invoke-WebRequest -Uri "http://localhost:8001/status" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
    Write-Host "✓ Kong Admin API (8001)" -ForegroundColor Green
    $successCount++
}
catch {
    Write-Host "✗ Kong Admin API (8001)" -ForegroundColor Red
    $failCount++
}

# Kong Proxy
try {
    $response = Invoke-WebRequest -Uri "http://localhost:8000/" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
    Write-Host "✓ Kong Proxy (8000)" -ForegroundColor Green
    $successCount++
}
catch {
    Write-Host "✗ Kong Proxy (8000)" -ForegroundColor Red
    $failCount++
}

# Floci (AWS Local)
try {
    $response = Invoke-WebRequest -Uri "http://localhost:4566/_floci/health" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
    Write-Host "✓ Floci AWS Local (4566)" -ForegroundColor Green
    $successCount++
}
catch {
    Write-Host "✗ Floci AWS Local (4566)" -ForegroundColor Red
    $failCount++
}

Write-Host "`n╔════════════════════════════════════════════════════════════════╗" -ForegroundColor Cyan
Write-Host "║                        SUMMARY REPORT                         ║" -ForegroundColor Cyan
Write-Host "╚════════════════════════════════════════════════════════════════╝`n" -ForegroundColor Cyan

Write-Host "Total Tests: $($successCount + $failCount)" -ForegroundColor White
Write-Host "✓ Passed: $successCount" -ForegroundColor Green
Write-Host "✗ Failed: $failCount`n" -ForegroundColor Red

if ($failCount -eq 0) {
    Write-Host "🎉 ALL TESTS PASSED! Platform is fully operational." -ForegroundColor Green
} else {
    Write-Host "⚠ Some tests failed. Review the output above." -ForegroundColor Yellow
}

Write-Host ""
