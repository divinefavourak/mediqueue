@echo off
REM Starts MediQueue. Run build.bat first.
REM Note the ';' classpath separator -- Windows uses ';' where Linux and macOS use ':'.

setlocal
cd /d "%~dp0"

if not exist out\ng\unilag\mediqueue\MediQueueApplication.class (
  echo   Not built yet. Run build.bat first.
  exit /b 1
)

REM -Ddemo.seed=true belongs here rather than in config.properties: that file ships inside
REM the packaged jar, so a value of true there would follow the build into production.
java -Ddemo.seed=true -cp "out;lib\postgresql-42.7.4.jar" ng.unilag.mediqueue.MediQueueApplication %*
endlocal
