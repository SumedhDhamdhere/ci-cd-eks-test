Write-Host "`n=== ECOMMERCE PLATFORM ENDPOINT TESTS ===" -ForegroundColor Cyan

$services = @(
    @{name="user-service"; port=8081},
    @{name="product-service"; port=8082},
    @{name="order-service"; port=8083},
    @{name="inventory-service"; port=8084},
    @{name="payment-service"; port=8085},
    @{name="notification-service"; port=8086}
)

$passCount = 0
$failCount = 0

foreach ($service in $services) {
    Write-Host "`n[*] $($service.name) (port $($service.port))" -ForegroundColor Yellow
    
    # Test health
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:$($service.port)/actuator/health" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
        $data = $response.Content | ConvertFrom-Json
        Write-Host "  [OK] /actuator/health - Status: $($data.status)" -ForegroundColor Green
        $passCount++
    }
    catch {
        Write-Host "  [FAIL] /actuator/health" -ForegroundColor Red
        $failCount++
    }
    
    # Test info
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:$($service.port)/actuator/info" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
        Write-Host "  [OK] /actuator/info" -ForegroundColor Green
        $passCount++
    }
    catch {
        Write-Host "  [FAIL] /actuator/info" -ForegroundColor Red
        $failCount++
    }
    
    # Test metrics
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:$($service.port)/actuator/metrics" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
        Write-Host "  [OK] /actuator/metrics" -ForegroundColor Green
        $passCount++
    }
    catch {
        Write-Host "  [FAIL] /actuator/metrics" -ForegroundColor Red
        $failCount++
    }
}

Write-Host "`n=== INFRASTRUCTURE TESTS ===" -ForegroundColor Cyan

# Kong Admin
try {
    $response = Invoke-WebRequest -Uri "http://localhost:8001/status" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
    Write-Host "[OK] Kong Admin API (8001)" -ForegroundColor Green
    $passCount++
}
catch {
    Write-Host "[FAIL] Kong Admin API (8001)" -ForegroundColor Red
    $failCount++
}

# Kong Proxy
try {
    $response = Invoke-WebRequest -Uri "http://localhost:8000/" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
    Write-Host "[OK] Kong Proxy (8000)" -ForegroundColor Green
    $passCount++
}
catch {
    Write-Host "[FAIL] Kong Proxy (8000)" -ForegroundColor Red
    $failCount++
}

# Floci
try {
    $response = Invoke-WebRequest -Uri "http://localhost:4566/_floci/health" -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
    Write-Host "[OK] Floci AWS Local (4566)" -ForegroundColor Green
    $passCount++
}
catch {
    Write-Host "[FAIL] Floci AWS Local (4566)" -ForegroundColor Red
    $failCount++
}

Write-Host "`n=== SUMMARY ===" -ForegroundColor Cyan
Write-Host "Total: $($passCount + $failCount) | Passed: $passCount | Failed: $failCount" -ForegroundColor White

if ($failCount -eq 0) {
    Write-Host "`nALL TESTS PASSED - Platform is operational!" -ForegroundColor Green
} else {
    Write-Host "`nSome tests failed." -ForegroundColor Yellow
}
Write-Host ""
