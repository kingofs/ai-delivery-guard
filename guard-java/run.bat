@echo off
REM ai-delivery-guard / guard-java - compile and run without Maven
REM usage:  run.bat            self-test + demo
REM         run.bat demo       demo only
REM         run.bat serve      http server on 8091
REM         run.bat case.json  check a json file

cd /d "%~dp0"

set "JAVAC_EXE=javac"
set "JAVA_EXE=java"
if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC_EXE=%JAVA_HOME%\bin\javac.exe"
  if exist "%JAVA_HOME%\bin\java.exe"  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)

if not exist out mkdir out
"%JAVAC_EXE%" -encoding UTF-8 -d out src\main\java\com\wsw\guard\*.java
if errorlevel 1 (
  echo [build failed]
  exit /b 1
)

"%JAVA_EXE%" -Dfile.encoding=UTF-8 -cp out com.wsw.guard.Main %*
