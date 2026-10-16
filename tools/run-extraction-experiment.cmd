@echo off
setlocal
for %%I in ("%~dp0..") do set "EXPERIMENT_ROOT=%%~fI"
if not defined JAVA_HOME (
  for /d %%J in ("%EXPERIMENT_ROOT%\.tools\jdk-21\*") do set "JAVA_HOME=%%~fJ"
)
set "EXPERIMENT_JAVA=java"
set "EXPERIMENT_JAVAC=javac"
if defined JAVA_HOME (
  set "EXPERIMENT_JAVA=%JAVA_HOME%\bin\java.exe"
  set "EXPERIMENT_JAVAC=%JAVA_HOME%\bin\javac.exe"
)
if not "%~2"=="" (
  echo Usage: run-extraction-experiment.cmd [output-directory] 1>&2
  exit /b 1
)
:freshClasses
set "EXPERIMENT_ID=%RANDOM%-%RANDOM%"
set "EXPERIMENT_CLASSES=%EXPERIMENT_ROOT%\.tools\extraction-classes\%EXPERIMENT_ID%"
if exist "%EXPERIMENT_CLASSES%" goto freshClasses
mkdir "%EXPERIMENT_CLASSES%"
if errorlevel 1 exit /b 1
set "EXPERIMENT_OUTPUT=%~1"
if not defined EXPERIMENT_OUTPUT set "EXPERIMENT_OUTPUT=%EXPERIMENT_ROOT%\.tools\extraction-runs\%EXPERIMENT_ID%"
"%EXPERIMENT_JAVAC%" --release 21 -encoding UTF-8 -d "%EXPERIMENT_CLASSES%" --source-path "%EXPERIMENT_ROOT%\backend\src\main\java" "%EXPERIMENT_ROOT%\backend\src\main\java\com\expensetracker\experiments\extraction\ExtractionExperiment.java"
if errorlevel 1 (
  echo Compilation failed. Set JAVA_HOME to a Java 21 JDK. 1>&2
  exit /b 1
)
"%EXPERIMENT_JAVA%" -cp "%EXPERIMENT_CLASSES%" com.expensetracker.experiments.extraction.ExtractionExperiment --output "%EXPERIMENT_OUTPUT%"
exit /b %ERRORLEVEL%
