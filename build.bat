@echo off
REM Compiles MediQueue with the JDK alone -- no Maven, no Gradle.
REM Output goes to out\, resources are copied alongside so they land on the classpath.

setlocal enabledelayedexpansion
cd /d "%~dp0"

set DRIVER=lib\postgresql-42.7.4.jar

if not exist "%DRIVER%" (
  echo.
  echo   Missing %DRIVER%
  echo   Download it from:
  echo   https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.4/postgresql-42.7.4.jar
  echo.
  exit /b 1
)

echo [build] Collecting sources ...
if exist sources.txt del sources.txt

REM Source paths are written RELATIVE to this folder, and that detail matters.
REM javac's @argfile splits on whitespace, so an absolute path containing a space --
REM as this project's own folder does ("csc 224, 202, 212") -- breaks the build with
REM "invalid flag: C:\Users\...\csc". Quoting the path instead trips a second problem:
REM inside quotes javac reads '\' as an escape character, so C:\Users becomes C:Users.
REM Relative paths under src\main\java contain neither spaces nor a drive prefix, so
REM they need no quoting and nothing gets escaped away.
set "ROOT=%CD%\"
for /r "src\main\java" %%f in (*.java) do (
  set "P=%%f"
  set "P=!P:%ROOT%=!"
  echo !P!>> sources.txt
)

echo [build] Compiling ...
if not exist out mkdir out
javac -d out -cp "%DRIVER%" @sources.txt
if errorlevel 1 (
  echo [build] FAILED
  exit /b 1
)

echo [build] Copying resources ...
xcopy /s /e /y /q src\main\resources\* out\ >nul

echo [build] Done. Run run.bat to start MediQueue.
endlocal
