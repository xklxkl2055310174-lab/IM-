@echo off
rem 启动 IM 服务端（Windows）
setlocal
set ROOT=%~dp0..
set JAR=%ROOT%\im-server\target\im-server.jar
if not exist "%JAR%" (
  echo 未找到 %JAR%，请先执行: mvn clean package
  exit /b 1
)
set PORT=%1
if "%PORT%"=="" set PORT=8080
echo 启动 IM 服务端: 0.0.0.0:%PORT%
java -jar "%JAR%" --port %PORT%
endlocal
