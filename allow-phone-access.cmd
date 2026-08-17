@echo off
rem Opens port 8090 to other devices on your home network, so a real phone running the
rem APK can reach the backend on this PC. Requires administrator rights, so it re-launches
rem itself elevated. Only the Private (home/work) firewall profile is touched - the rule
rem does not apply on public Wi-Fi.
setlocal
title Dividend Stream - allow phone access

net session >nul 2>&1
if errorlevel 1 (
    echo   Requesting administrator rights...
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

echo.
echo   Adding an inbound rule for TCP 8090 on the Private network profile...
netsh advfirewall firewall delete rule name="Dividend Stream backend (8090)" >nul 2>&1
netsh advfirewall firewall add rule name="Dividend Stream backend (8090)" dir=in action=allow protocol=TCP localport=8090 profile=private

echo.
echo   Done. Your phone can now reach this PC on port 8090,
echo   as long as both are on the same Wi-Fi.
echo.
echo   To undo this later:
echo     netsh advfirewall firewall delete rule name="Dividend Stream backend (8090)"
echo.
pause
