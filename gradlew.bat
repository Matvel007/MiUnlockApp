@echo off
setlocal
rem The trailing backslash from %%~dp0 escapes the closing quote in -p.
set APP_HOME=%~dp0.
set VERSION=8.7
if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set CACHE=%GRADLE_USER_HOME%\wrapper\dists\gradle-%VERSION%-bin\local
set DIST=%CACHE%\gradle-%VERSION%
set ZIP=%CACHE%\gradle-%VERSION%-bin.zip
if exist "%DIST%\bin\gradle.bat" goto run
if not exist "%CACHE%" mkdir "%CACHE%"
echo Downloading Gradle %VERSION%...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip' -OutFile '%ZIP%'"
if errorlevel 1 exit /b 1
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%ZIP%' '%CACHE%'"
if errorlevel 1 exit /b 1
:run
call "%DIST%\bin\gradle.bat" -p "%APP_HOME%" %*
endlocal
