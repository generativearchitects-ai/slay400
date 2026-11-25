@echo off
rem slay400-gateway: starts the gateway of this distribution (gateway kickoff 10, decision 147).
rem
rem Java: %JAVA_HOME%\bin\java.exe when JAVA_HOME is set, otherwise java.exe from the PATH; version 21 or later, otherwise one
rem sentence and exit status 3. %JAVA_OPTS% and %SLAY400_GATEWAY_OPTS%, when set, are passed to the JVM. Every argument is
rem passed on as given, and the exit status is the gateway's. A symbolic link to this script is followed. Nothing is written,
rem no network is used.
setlocal EnableExtensions DisableDelayedExpansion

rem Where this distribution is: a symbolic link (attribute l) is followed to the script itself.
set "SCRIPT=%~f0"
set "ATTRIBUTES=%~a0"
if not "%ATTRIBUTES:l=%"=="%ATTRIBUTES%" (
  for /f "usebackq delims=" %%T in (`powershell -NoProfile -NonInteractive -Command "(Get-Item -LiteralPath $env:SCRIPT).Target"`) do set "SCRIPT=%%T"
)
for %%S in ("%SCRIPT%") do set "BIN=%%~dpS"
for %%H in ("%BIN%..") do set "GATEWAY_HOME=%%~fH"

rem Which Java.
set "JAVACMD="
if defined JAVA_HOME (
  set "JAVACMD=%JAVA_HOME%\bin\java.exe"
) else (
  for %%J in (java.exe) do set "JAVACMD=%%~$PATH:J"
)
if not defined JAVACMD goto nojava
if not exist "%JAVACMD%" goto nojava

rem Its version: the first quoted text of "java -version", for example 21.0.13 or 1.8.0_402.
set "JAVAVERSION="
for /f tokens^=2^ delims^=^" %%V in ('"%JAVACMD%" -version 2^>^&1') do (
  if not defined JAVAVERSION set "JAVAVERSION=%%V"
)
set "MAJOR="
for /f "tokens=1,2 delims=.-_+" %%A in ("%JAVAVERSION%") do (
  if "%%A"=="1" (set "MAJOR=%%B") else (set "MAJOR=%%A")
)
set /a MAJORNUMBER=MAJOR+0 2>nul
if "%MAJORNUMBER%"=="" set "MAJORNUMBER=0"
if %MAJORNUMBER% LSS 21 goto oldjava

"%JAVACMD%" %JAVA_OPTS% %SLAY400_GATEWAY_OPTS% "-Dslay400.gateway.home=%GATEWAY_HOME%" -cp "%GATEWAY_HOME%\lib\*" ai.generativearchitects.slay400.gateway.server.Main %*
exit /b %ERRORLEVEL%

:nojava
echo slay400-gateway needs Java 21 or later and found no Java: set JAVA_HOME or put java on the PATH. 1>&2
exit /b 3

:oldjava
if not defined JAVAVERSION set "JAVAVERSION=no version"
echo slay400-gateway needs Java 21 or later and found %JAVAVERSION% at %JAVACMD%. 1>&2
exit /b 3
