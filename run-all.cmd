@echo off
rem One double-click: backend in its own window, then emulator + app.
setlocal
title Dividend Stream

echo.
echo   Starting the backend in a separate window...
rem "cmd /c call <path>" rather than "cmd /c <path>": the repository path contains a
rem space, and cmd only preserves the surrounding quotes under a narrow set of
rem conditions. Starting the command with a bare word sidesteps the rule entirely.
start "Dividend Stream - Backend" cmd /c call "%~dp0run-backend.cmd"

echo   Waiting for it to accept connections on port 8090...
set /a TRIES=0
:wait
netstat -ano -p TCP | findstr /r /c:":8090 .*LISTENING" >nul
if not errorlevel 1 goto ready
set /a TRIES+=1
if %TRIES% gtr 120 (
    echo.
    echo   The backend has not come up after 4 minutes.
    echo   Check its window for errors - continuing anyway.
    echo.
    goto ready
)
timeout /t 2 >nul
goto wait

:ready
echo   Backend is up.
echo.
call "%~dp0run-app.cmd"
