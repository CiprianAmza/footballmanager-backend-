@echo off
rem Starts the Tactic Arena simulator on Windows: run.bat [results-repository-url]
if "%~1"=="" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1"
) else (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run.ps1" -RepoUrl "%~1"
)
