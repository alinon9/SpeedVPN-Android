@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "DIR=app\src\main\jni\hev-socks5-tunnel"
set "TAG=2.18.0"
set "COMMIT=d9dca26c7ad0e494492244f0309e80ee583e739e"
set "REPO=https://github.com/heiher/hev-socks5-tunnel"

if not exist "%DIR%\.git" (
  if exist "%DIR%" rmdir /s /q "%DIR%"
  git clone --branch %TAG% --depth 1 --recursive %REPO% "%DIR%"
) else (
  git -C "%DIR%" fetch --tags --depth 1 origin "refs/tags/%TAG%:refs/tags/%TAG%"
)

git -C "%DIR%" checkout --detach %COMMIT%
if errorlevel 1 exit /b 1
git -C "%DIR%" submodule update --init --recursive
if errorlevel 1 exit /b 1

for /f "delims=" %%H in ('git -C "%DIR%" rev-parse HEAD') do set "HEAD=%%H"
if /I not "!HEAD!"=="%COMMIT%" (
  echo ERROR - hev-socks5-tunnel HEAD=!HEAD!, expected %COMMIT%
  exit /b 1
)

echo OK - hev-socks5-tunnel pinned to %TAG% (!HEAD!)
echo Now open this folder in Android Studio.
