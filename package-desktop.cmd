@echo off
rem Builds a self-contained Windows application: a real .exe with its own Java runtime
rem bundled, so the machine it runs on needs neither a JDK nor Gradle.
rem
rem Output: dist\DividendStream\Dividend Stream.exe
setlocal

rem Must match packageVersion in desktop\build.gradle.kts, which names the .msi jpackage
rem produces. Keeping it in one place here means a bump touches two files, not four.
set "APP_VERSION=1.0.13"

title Dividend Stream - build desktop app

call :find_build_jdk
if not defined JAVA_HOME (
    echo.
    echo   Could not find a JDK 21 to build with.
    echo.
    pause
    exit /b 1
)

call :find_jpackage_jdk
if not defined PACKAGE_JDK (
    echo.
    echo   Could not find a JDK containing jpackage.exe.
    echo.
    echo   The JetBrains runtime shipped with Android Studio omits jpackage, so packaging
    echo   needs a full JDK. Install one (for example Temurin 21) and run this again.
    echo.
    pause
    exit /b 1
)

echo.
echo   Building with : %JAVA_HOME%
echo   Packaging with: %PACKAGE_JDK%
echo.
echo   This takes several minutes and downloads nothing after the first run.
echo.

cd /d "%~dp0desktop"
rem createDistributable produces the portable folder; packageMsi wraps it in an installer.
rem The Compose plugin downloads the WiX toolset itself the first time packageMsi runs.
call "%~dp0desktop\gradlew.bat" createDistributable packageMsi "-Pdividendstream.jpackage.home=%PACKAGE_JDK%"
if errorlevel 1 (
    echo.
    echo   Build failed. See the errors above.
    pause
    exit /b 1
)

set "IMAGE=%~dp0desktop\build\compose\binaries\main\app\Dividend Stream"
set "MSI=%~dp0desktop\build\compose\binaries\main\msi\Dividend Stream-%APP_VERSION%.msi"

if not exist "%IMAGE%\Dividend Stream.exe" (
    echo.
    echo   The build reported success but no application image was produced at:
    echo     %IMAGE%
    pause
    exit /b 1
)

if not exist "%~dp0dist" mkdir "%~dp0dist"

if exist "%MSI%" (
    echo   Copying the installer to dist ...
    copy /y "%MSI%" "%~dp0dist\DividendStream-%APP_VERSION%-installer.msi" >nul
) else (
    echo   No .msi was produced; the portable folder below still works.
)

echo.
echo   Done.
echo     Installer: %~dp0dist\DividendStream-%APP_VERSION%-installer.msi
echo     Portable : %IMAGE%\Dividend Stream.exe
echo.
echo   Install the .msi for a Start Menu entry, or copy the portable folder anywhere -
echo   including to another PC, which needs no Java installed.
echo.
pause
exit /b


:find_build_jdk
set "CANDIDATE=%ProgramFiles%\Android\Android Studio\jbr"
if exist "%CANDIDATE%\bin\java.exe" (
    set "JAVA_HOME=%CANDIDATE%"
    goto :eof
)
if exist "%JAVA_HOME%\bin\java.exe" goto :eof
set "JAVA_HOME="
goto :eof


rem jpackage is absent from the JetBrains runtime, so packaging needs a full JDK.
:find_jpackage_jdk
set "PACKAGE_JDK="
for %%d in ("%ProgramFiles%\Java\jdk-23" "%ProgramFiles%\Java\jdk-21" "%ProgramFiles%\Eclipse Adoptium\jdk-21" "%ProgramFiles%\Microsoft\jdk-21") do (
    if not defined PACKAGE_JDK if exist "%%~d\bin\jpackage.exe" set "PACKAGE_JDK=%%~d"
)
if defined PACKAGE_JDK goto :eof
if exist "%JAVA_HOME%\bin\jpackage.exe" set "PACKAGE_JDK=%JAVA_HOME%"
goto :eof
