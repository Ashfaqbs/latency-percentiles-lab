@echo off
echo --- Resetting all samples ---
curl -s -X POST "http://localhost:8090/api/reset"
echo.

echo --- Round 1: 20 requests ---
curl -s -X POST "http://localhost:8090/api/simulate/batch?count=20"
echo.
curl -s "http://localhost:8090/api/percentiles"
echo.
echo Waiting 15s for Prometheus to scrape...
timeout /t 15 /nobreak >nul

echo --- Round 2: 100 requests ---
curl -s -X POST "http://localhost:8090/api/simulate/batch?count=100"
echo.
curl -s "http://localhost:8090/api/percentiles"
echo.
echo Waiting 15s...
timeout /t 15 /nobreak >nul

echo --- Round 3: 200 requests ---
curl -s -X POST "http://localhost:8090/api/simulate/batch?count=200"
echo.
curl -s "http://localhost:8090/api/percentiles"
echo.
echo Waiting 15s...
timeout /t 15 /nobreak >nul

echo --- Round 4: 500 requests ---
curl -s -X POST "http://localhost:8090/api/simulate/batch?count=500"
echo.
curl -s "http://localhost:8090/api/percentiles"
echo.

echo === Done. Open Grafana at http://localhost:3000 (admin / admin) ===
