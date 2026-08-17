@echo off
rem Runs the backend against the cloud database in backend\.env, rather than the in-process
rem PostgreSQL that run-backend.cmd uses.
rem
rem Spring Boot does not read .env files, so this loads them into the environment first.
setlocal enabledelayedexpansion
title Dividend Stream - Backend (cloud database)

call :find_jdk
if not defined JAVA_HOME (
    echo.
    echo   Could not find a JDK 21. Install Android Studio, or set JAVA_HOME to a JDK 21.
    echo.
    pause
    exit /b 1
)

set "ENVFILE=%~dp0backend\.env"
if not exist "%ENVFILE%" (
    echo.
    echo   Missing "%ENVFILE%".
    echo   Copy backend\.env.example to backend\.env and fill it in.
    echo.
    pause
    exit /b 1
)

rem Blank lines and # comments are skipped. Values may contain '=' - only the first splits.
for /f "usebackq tokens=1,* delims==" %%a in ("%ENVFILE%") do (
    set "KEY=%%a"
    if not "!KEY!"=="" if not "!KEY:~0,1!"=="#" set "!KEY!=%%b"
)

if not defined DATABASE_URL (
    echo   backend\.env does not define DATABASE_URL.
    pause
    exit /b 1
)

set "SERVER_PORT=8090"

echo.
echo   Database : %DATABASE_URL%
echo   API      : http://localhost:%SERVER_PORT%
echo.
echo   A VPN will break the database connection - the tunnel accepts the TCP handshake
echo   but never forwards the traffic, and this hangs with a read timeout.
echo.

cd /d "%~dp0backend"
call "%~dp0backend\gradlew.bat" bootRun

echo.
pause
exit /b


:find_jdk
set "CANDIDATE=%ProgramFiles%\Android\Android Studio\jbr"
if exist "%CANDIDATE%\bin\java.exe" (
    set "JAVA_HOME=%CANDIDATE%"
    goto :eof
)
set "CANDIDATE=%LOCALAPPDATA%\Programs\Android Studio\jbr"
if exist "%CANDIDATE%\bin\java.exe" (
    set "JAVA_HOME=%CANDIDATE%"
    goto :eof
)
if exist "%JAVA_HOME%\bin\java.exe" goto :eof
set "JAVA_HOME="
goto :eof
