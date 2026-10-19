@echo off
rem ============================================================================
rem  Windows fallback ONLY. The real entry points are the bash scripts:
rem    bash sync-notelab-java.sh | sync-notelab-b.sh | sync-notelab-c.sh
rem         | sync-ai-lab.sh | sync-all.sh
rem  (Implementation: sync-repo.sh -> server-side ops/sync-deploy.sh)
rem
rem  Use this file when bash is not on PATH; it locates the bash shipped with
rem  Git for Windows and runs sync-repo.sh through it.
rem  Preferred fix (one time): add  C:\Program Files\Git\bin  to PATH.
rem
rem  Usage: sync.cmd <repo|all> [--build|--no-build|--dry-run|-h]
rem    repo in: notelab-java | notelab-b | notelab-c | ai-lab | home | all
rem
rem  NOTE: keep this file ASCII-only. Chinese text in a .cmd gets mangled when
rem  the console codepage (936 vs 65001) disagrees with the file encoding.
rem ============================================================================
setlocal

set "BASH=%ProgramFiles%\Git\bin\bash.exe"
if not exist "%BASH%" set "BASH=%LOCALAPPDATA%\Programs\Git\bin\bash.exe"
if not exist "%BASH%" (
	echo [x] Git Bash not found: "%ProgramFiles%\Git\bin\bash.exe"
	echo     Install Git for Windows, or add Git\bin to PATH and run:
	echo       bash sync-notelab-b.sh
	exit /b 1
)

"%BASH%" "%~dp0sync-repo.sh" %*
exit /b %ERRORLEVEL%
