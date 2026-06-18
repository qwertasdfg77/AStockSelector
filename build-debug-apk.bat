@echo off
setlocal

if "%ANDROID_HOME%"=="" if "%ANDROID_SDK_ROOT%"=="" (
    echo ANDROID_HOME or ANDROID_SDK_ROOT is not set.
    echo Please install Android SDK 35 or open this project with Android Studio first.
    exit /b 1
)

cd /d %~dp0
if exist "%~dp0gradlew.bat" (
    call "%~dp0gradlew.bat" --no-daemon :app:assembleDebug
) else (
    where gradle >nul 2>nul
    if errorlevel 1 (
        echo Gradle was not found in PATH.
        echo Please install Gradle 8.9 or open this project with Android Studio.
        exit /b 1
    )
    gradle --no-daemon :app:assembleDebug
)

if errorlevel 1 exit /b %errorlevel%

echo.
echo APK: %~dp0app\build\outputs\apk\debug\app-debug.apk
pause
