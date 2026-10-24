@echo off
rem 启动 IM 命令行客户端（Windows）
setlocal
set ROOT=%~dp0..
set JAR=%ROOT%\im-client\target\im-client.jar
if not exist "%JAR%" (
  echo 未找到 %JAR%，请先执行: mvn clean package
  exit /b 1
)
set HOST=%1
if "%HOST%"=="" set HOST=127.0.0.1
set PORT=%2
if "%PORT%"=="" set PORT=8080
set USER_NAME=%3
if "%USER_NAME%"=="" set USER_NAME=alice
echo 连接 IM 服务端: %HOST%:%PORT%，用户: %USER_NAME%
java -jar "%JAR%" --host %HOST% --port %PORT% --user %USER_NAME%
endlocal
