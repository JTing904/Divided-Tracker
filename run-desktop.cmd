@echo off
rem Runs the Windows desktop app from source. Everything - PostgreSQL, the API and the UI -
rem runs inside this one process, so there is no backend window to start first.
rem
rem For an installed copy that does not need Gradle or a JDK, run package-desktop.cmd once
rem and use the shortcut it creates.
setlocal
title Dividend Stream - Desktop

call :find_jdk
if not defined JAVA_HOME (
    echo.
    echo   Could not find a JDK 21. Install Android Studio, or set JAVA_HOME to a JDK 21.
    echo.
    pause
    exit /b 1
)

cd /d "%~dp0desktop"
if errorlevel 1 (
    echo   Could not enter "%~dp0desktop".
    pause
    exit /b 1
)

echo.
echo   Starting Dividend Stream. The first launch initialises the database and is slower.
echo.
call "%~dp0desktop\gradlew.bat" run

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
