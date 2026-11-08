@echo off
rem Launches the JFR MCP server over stdio (Windows). stdout is reserved for the MCP protocol.
setlocal enabledelayedexpansion
set "ROOT=%~dp0.."
set "JAR=%ROOT%\mcp-server\target\jfr-agent-mcp.jar"
set "JAVA="
for /f "usebackq delims=" %%J in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0find-jdk.ps1"`) do set "JAVA=%%J"
if "%JAVA%"=="" (
  echo [jfr-agent] ERROR: no JDK found. Install JDK 25 ^(https://adoptium.net^) and set JAVA_HOME or JFR_AGENT_JAVA_HOME, or add {"javaHome":"C:\\path\\to\\jdk"} to %%USERPROFILE%%\.jfr-agent\config.json 1>&2
  exit /b 1
)
if not exist "%JAR%" (
  echo [jfr-agent] Server jar missing, building with Maven ^(one-time^)... 1>&2
  for %%I in ("%JAVA%\..\..") do set "JH=%%~fI"
  pushd "%ROOT%\mcp-server"
  set "JAVA_HOME=!JH!"
  call mvnw.cmd -q -DskipTests package 1>&2
  if errorlevel 1 ( echo [jfr-agent] ERROR: build failed 1>&2 & popd & exit /b 1 )
  popd
)
if "%JFR_AGENT_XMX%"=="" set "JFR_AGENT_XMX=4g"
"%JAVA%" -Xss4m -XX:+UseG1GC -Xmx%JFR_AGENT_XMX% --add-exports=jdk.jfr/jdk.jfr.internal.consumer=ALL-UNNAMED --add-exports=jdk.jfr/jdk.jfr.internal.consumer.filter=ALL-UNNAMED -jar "%JAR%"
