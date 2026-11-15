@echo off
chcp 65001 >nul
cd /d "%~dp0"
set /p TOKEN=请输入服务端口令(英文数字, 留空使用 NetPump123): 
if "%TOKEN%"=="" set "TOKEN=NetPump123"
echo.
echo 端口: 5201 TCP + UDP
echo 手机输入对应 IP、5201 和设置的服务端口令。
echo 仅在你授权的网络上开放端口，Ctrl+C 可停止。
echo.
where py >nul 2>&1
if %errorlevel% equ 0 (
    py -3 netpump_server.py --bind 0.0.0.0 --port 5201 --token "%TOKEN%"
) else (
    python netpump_server.py --bind 0.0.0.0 --port 5201 --token "%TOKEN%"
)
pause
