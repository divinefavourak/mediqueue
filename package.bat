@echo off
REM Packages MediQueue as ONE runnable file: dist\mediqueue.jar
REM Deployment then needs nothing but a JRE:  java -jar mediqueue.jar

setlocal enabledelayedexpansion
cd /d "%~dp0"

set DRIVER=lib\postgresql-42.7.4.jar
set STAGE=build\jar
set OUT=dist\mediqueue.jar

if not exist "%DRIVER%" (
  echo   Missing %DRIVER% -- run build.bat first for the download link.
  exit /b 1
)

REM ---------------------------------------------------------------------------
REM Locate the 'jar' tool.
REM
REM It is NOT enough to assume it sits beside javac. Oracle installs put a stub
REM directory on PATH (Common Files\Oracle\Java\javapath) holding only java, javac,
REM javaw and jshell -- so javac resolves fine while jar is nowhere to be found.
REM Look in JAVA_HOME, then PATH, then the usual JDK install locations.
REM ---------------------------------------------------------------------------
set "JAR_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jar.exe" set "JAR_EXE=%JAVA_HOME%\bin\jar.exe"
if not defined JAR_EXE for /f "delims=" %%j in ('where jar 2^>nul') do set "JAR_EXE=%%j"
if not defined JAR_EXE for /d %%d in ("C:\Program Files\Java\jdk*") do (
  if exist "%%d\bin\jar.exe" set "JAR_EXE=%%d\bin\jar.exe"
)
if not defined JAR_EXE for /d %%d in ("C:\Program Files\Eclipse Adoptium\*") do (
  if exist "%%d\bin\jar.exe" set "JAR_EXE=%%d\bin\jar.exe"
)
if not defined JAR_EXE (
  echo.
  echo   Could not find the 'jar' tool. It ships with the JDK, not the JRE.
  echo   Set JAVA_HOME to your JDK folder and try again, for example:
  echo       setx JAVA_HOME "C:\Program Files\Java\jdk-19"
  echo   then open a new terminal.
  echo.
  exit /b 1
)
echo [package] Using %JAR_EXE%

echo [package] Compiling ...
if exist "%STAGE%" rmdir /s /q "%STAGE%"
mkdir "%STAGE%" 2>nul
if not exist dist mkdir dist

REM Relative paths only: javac's @argfile splits an absolute path on the space in this
REM project's folder name. See build.bat for the full explanation.
if exist sources.txt del sources.txt
set "ROOT=%CD%\"
for /r "src\main\java" %%f in (*.java) do (
  set "P=%%f"
  set "P=!P:%ROOT%=!"
  echo !P!>> sources.txt
)

REM --release 17 so the jar runs on any JRE 17 or newer, not just the JDK that built it.
javac --release 17 -d "%STAGE%" -cp "%DRIVER%" @sources.txt
if errorlevel 1 ( echo [package] FAILED & exit /b 1 )

echo [package] Adding resources ...
xcopy /s /e /y /q src\main\resources\* "%STAGE%\" >nul

echo [package] Unpacking the JDBC driver ...
pushd "%STAGE%"
"%JAR_EXE%" xf "%ROOT%%DRIVER%"
REM The driver's own signature files no longer match once the contents are repacked, and
REM the JVM would refuse to load the classes.
del /q META-INF\MANIFEST.MF META-INF\*.SF META-INF\*.DSA META-INF\*.RSA 2>nul
popd

echo [package] Building %OUT% ...
"%JAR_EXE%" --create --file "%OUT%" --main-class ng.unilag.mediqueue.MediQueueApplication -C "%STAGE%" .
if errorlevel 1 ( echo [package] FAILED & exit /b 1 )

echo [package] Done. Run it with:  java -jar %OUT%
endlocal
