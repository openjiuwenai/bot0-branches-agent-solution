@echo off
REM ================================================================
REM start.bat - Windows startup script
REM ================================================================
REM Usage: set EDP_AGENT_MODEL_API_KEY=sk-xxx && start.bat
REM ================================================================

setlocal enabledelayedexpansion

REM --- JAR path ---
set JAR_FILE=..\..\agent\edp-agent-java\engine\target\edp-agent-engine-0.1.0-SNAPSHOT.jar

REM --- Check JAR exists ---
if not exist "%JAR_FILE%" (
    echo [ERROR] JAR not found: %JAR_FILE%
    echo Please build first: cd ..\..\agent\edp-agent-java\engine ^&^& mvn clean package -DskipTests
    exit /b 1
)

REM --- Check API key ---
if "%EDP_AGENT_MODEL_API_KEY%"=="" (
    echo [WARN] EDP_AGENT_MODEL_API_KEY not set, using default in application.yml
)

echo [INFO] Starting edp-agent (smart-customer-service)...
echo [INFO] JAR: %JAR_FILE%
echo [INFO] Config: application.yml
echo.

java -jar "%JAR_FILE%" --spring.config.additional-location=file:./application.yml

pause
