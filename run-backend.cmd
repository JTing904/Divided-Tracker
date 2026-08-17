@echo off
rem Starts the Dividend Stream backend with an in-process PostgreSQL.
rem No Docker and no PostgreSQL install required.
setlocal
title Dividend Stream - Backend

call :find_jdk
if not defined JAVA_HOME (
    echo.
    echo   Could not find a JDK 21.
    echo   Android Studio bundles one at "%%ProgramFiles%%\Android\Android Studio\jbr".
    echo   Install Android Studio, or set JAVA_HOME to a JDK 21 and run this again.
    echo.
    pause
    exit /b 1
)

set "SERVER_PORT=8090"

rem Port 8080 is taken on this machine, hence 8090 - the debug app already points there.
rem A previously stopped run can leave its forked JVM holding the port, so check first.
call :check_port
if errorlevel 1 exit /b 1

cd /d "%~dp0backend"
if errorlevel 1 (
    echo   Could not enter "%~dp0backend".
    pause
    exit /b 1
)

echo.
echo   Backend starting on http://localhost:%SERVER_PORT%
echo   JDK: %JAVA_HOME%
echo.
echo   Leave this window open. Ctrl+C stops the server.
echo.

rem Called by explicit path: NoDefaultCurrentDirectoryInExePath keeps the working
rem directory off the command search path on some machines, and a bare "gradlew.bat"
rem is then not found even though we just cd'd next to it.
call "%~dp0backend\gradlew.bat" bootTestRun

echo.
echo   Backend stopped.
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
rem Fall back to whatever is already configured. The system JDK on this machine is 26,
rem which Gradle 8.14 / Spring Boot 3.5 reject, so this is a last resort.
if exist "%JAVA_HOME%\bin\java.exe" goto :eof
set "JAVA_HOME="
goto :eof


:check_port
set "HOLDER="
for /f "tokens=5" %%p in ('netstat -ano -p TCP ^| findstr /r /c:":%SERVER_PORT% .*LISTENING"') do set "HOLDER=%%p"
if not defined HOLDER exit /b 0

echo.
echo   Port %SERVER_PORT% is already in use by process %HOLDER%.
echo   That is usually a leftover backend from an earlier run.
echo.
choice /c YN /m "  Stop process %HOLDER% and continue"
if errorlevel 2 (
    echo   Left it running. Nothing started.
    pause
    exit /b 1
)
taskkill /pid %HOLDER% /f >nul 2>&1
timeout /t 2 >nul
exit /b 0
