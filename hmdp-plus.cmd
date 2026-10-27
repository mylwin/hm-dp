@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0hmdp-plus.ps1" %*
exit /b %ERRORLEVEL%
