@echo off
setlocal
cd /d "%~dp0"
chcp 65001 >nul
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp001_INSTALL.ps1" %*
set EC=%ERRORLEVEL%
echo.
if not "%EC%"=="0" (echo [ERROR] exit code %EC%) else (echo [OK] done)
echo.
pause
exit /b %EC%
