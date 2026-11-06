@echo off
chcp 65001 >nul
title GoShare 一键启动
cd /d "%~dp0"
echo ========================================
echo   GoShare 顺风拼车 - 一键启动
echo ========================================
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File ".\scripts\start-all.ps1"
echo.
echo ========================================
echo   启动完成！请在浏览器打开: http://localhost:5173
echo ========================================
pause