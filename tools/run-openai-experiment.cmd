@echo off
setlocal
for %%I in ("%~dp0..") do set "EXPERIMENT_ROOT=%%~fI"
if not defined JAVA_HOME (
  for /d %%J in ("%EXPERIMENT_ROOT%\.tools\jdk-21\*") do set "JAVA_HOME=%%~fJ"
)
set "EXPERIMENT_JAVA=java"
if defined JAVA_HOME set "EXPERIMENT_JAVA=%JAVA_HOME%\bin\java.exe"
pushd "%EXPERIMENT_ROOT%\backend"
call mvnw.cmd --batch-mode -DskipTests package
if errorlevel 1 (
  popd
  exit /b 1
)
popd
"%EXPERIMENT_JAVA%" -Dloader.main=com.expensetracker.experiments.extraction.openai.OpenAiExtractionExperiment -cp "%EXPERIMENT_ROOT%\backend\target\backend-0.1.0-SNAPSHOT.jar" org.springframework.boot.loader.launch.PropertiesLauncher %*
exit /b %ERRORLEVEL%
