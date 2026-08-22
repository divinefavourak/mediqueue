@echo off
REM Wipes the demo database and rebuilds it from scratch.
REM
REM Use this when the demo has been clicked into a strange state, or when you want
REM today's queues to line up with the current time again. The seed is deterministic,
REM so you get the same clinic back every time.
REM
REM DESTRUCTIVE: every account and appointment in the local `mediqueue` database is
REM deleted. It only touches the local Docker container, never a deployment.

setlocal
cd /d "%~dp0"

echo.
echo   This deletes ALL data in the local mediqueue database.
echo   The demo accounts and a fortnight of activity will be recreated.
echo.
set /p CONFIRM=  Type YES to continue:
if /i not "%CONFIRM%"=="YES" (
  echo   Cancelled.
  exit /b 1
)

echo.
echo [reset] Stopping MediQueue if it is running ...
for /f "tokens=2 delims=," %%p in ('tasklist /fi "imagename eq java.exe" /fo csv /nh 2^>nul') do (
  taskkill /pid %%~p /f >nul 2>&1
)

echo [reset] Recreating the database ...
docker exec mediqueue-db psql -U postgres -q -c "DROP DATABASE IF EXISTS mediqueue;" -c "CREATE DATABASE mediqueue;"
if errorlevel 1 (
  echo   Could not reach the database. Is the container running?  docker start mediqueue-db
  exit /b 1
)

echo [reset] Starting MediQueue. First boot seeds the data and takes about 30 seconds.
echo.
call run.bat
endlocal
