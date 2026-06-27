@echo off
setlocal
cd /d "%~dp0"

if exist "%GRADLE_HOME%\bin\gradle.bat" (
  "%GRADLE_HOME%\bin\gradle.bat" build
  exit /b %ERRORLEVEL%
)

where gradle >nul 2>nul
if %ERRORLEVEL%==0 (
  gradle build
  exit /b %ERRORLEVEL%
)

echo Gradle was not found.
echo Set GRADLE_HOME or add Gradle to PATH.
exit /b 1
