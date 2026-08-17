@echo off
rem Boots an emulator if none is attached, builds and installs the debug APK, launches it.
setlocal enabledelayedexpansion
title Dividend Stream - Android

call :find_jdk
if not defined JAVA_HOME (
    echo.
    echo   Could not find a JDK 21. Install Android Studio, or set JAVA_HOME to a JDK 21.
    echo.
    pause
    exit /b 1
)

call :find_sdk
if not defined ANDROID_SDK (
    echo.
    echo   Could not find the Android SDK.
    echo   Set ANDROID_HOME, or install it via Android Studio ^> SDK Manager.
    echo.
    pause
    exit /b 1
)

set "ADB=%ANDROID_SDK%\platform-tools\adb.exe"
set "EMULATOR=%ANDROID_SDK%\emulator\emulator.exe"

call :ensure_device
if errorlevel 1 exit /b 1

cd /d "%~dp0android"
if errorlevel 1 (
    echo   Could not enter "%~dp0android".
    pause
    exit /b 1
)

echo.
echo   Building and installing the debug APK...
echo.
rem Explicit path - see the note in run-backend.cmd.
call "%~dp0android\gradlew.bat" installDebug
if errorlevel 1 (
    echo.
    echo   Build failed. See the errors above.
    pause
    exit /b 1
)

echo.
echo   Launching Dividend Stream...
"%ADB%" shell am start -n com.dividendstream.app/.MainActivity >nul

echo.
echo   Done. The app is running on the device.
echo   If every request fails, the backend is not up - run run-backend.cmd.
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


:find_sdk
set "ANDROID_SDK="
if exist "%ANDROID_HOME%\platform-tools\adb.exe" (
    set "ANDROID_SDK=%ANDROID_HOME%"
    goto :eof
)
if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" (
    set "ANDROID_SDK=%ANDROID_SDK_ROOT%"
    goto :eof
)
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
    set "ANDROID_SDK=%LOCALAPPDATA%\Android\Sdk"
    goto :eof
)
goto :eof


:ensure_device
set "DEVICE="
for /f "skip=1 tokens=1,2" %%a in ('"%ADB%" devices') do (
    if "%%b"=="device" if not defined DEVICE set "DEVICE=%%a"
)
if defined DEVICE (
    echo   Using device !DEVICE!
    exit /b 0
)

set "AVD="
for /f "delims=" %%a in ('"%EMULATOR%" -list-avds 2^>nul') do (
    if not defined AVD set "AVD=%%a"
)
if not defined AVD (
    echo.
    echo   No device attached and no emulator image found.
    echo   Create one in Android Studio ^> Device Manager, then run this again.
    echo.
    pause
    exit /b 1
)

echo   No device attached. Starting emulator "!AVD!"...
start "Android Emulator" "%EMULATOR%" -avd "!AVD!"

"%ADB%" wait-for-device

rem wait-for-device returns as soon as adb connects, which is long before Android is usable.
set /a TRIES=0
:waitboot
"%ADB%" shell getprop sys.boot_completed 2>nul | findstr /r "^1" >nul
if not errorlevel 1 (
    echo   Emulator ready.
    exit /b 0
)
set /a TRIES+=1
if %TRIES% gtr 100 (
    echo.
    echo   Emulator did not finish booting in time. Wait for it, then run this again.
    echo.
    pause
    exit /b 1
)
timeout /t 3 >nul
goto waitboot
