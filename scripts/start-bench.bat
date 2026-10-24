@echo off
rem 运行 IM 压测（Windows）
setlocal
set ROOT=%~dp0..
set JAR=%ROOT%\im-bench\target\im-bench.jar
if not exist "%JAR%" (
  echo 未找到 %JAR%，请先执行: mvn clean package
  exit /b 1
)
set HOST=%1
if "%HOST%"=="" set HOST=127.0.0.1
set PORT=%2
if "%PORT%"=="" set PORT=8080
set CONNECTIONS=%3
if "%CONNECTIONS%"=="" set CONNECTIONS=8
set MESSAGES=%4
if "%MESSAGES%"=="" set MESSAGES=500
echo 压测 IM 服务端: %HOST%:%PORT%
java -jar "%JAR%" --host %HOST% --port %PORT% --connections %CONNECTIONS% --messages %MESSAGES%
endlocal
