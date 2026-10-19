@echo off
rem Thin shim: Windows blocks .ps1 by default, so run the real script with -ExecutionPolicy Bypass.
rem The implementation (docs, validation, ssh, exit code) lives in sync-deploy.ps1.
rem Usage: sync-notelab-b [--build ^| --no-build ^| --dry-run ^| -h]
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0sync-deploy.ps1" -Repo notelab-b %*
exit /b %ERRORLEVEL%
