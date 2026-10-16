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

set "TCP_SOURCE=%DIR%\src\hev-socks5-session-tcp.c"
set "LWIP_OPTS=%DIR%\third-part\lwip\src\ports\include\lwipopts.h"
if not exist "%TCP_SOURCE%" (
  echo ERROR - missing native TCP session source: %TCP_SOURCE%
  exit /b 1
)
if not exist "%LWIP_OPTS%" (
  echo ERROR - missing lwIP options header: %LWIP_OPTS%
  exit /b 1
)

python scripts\e2e\patch_hev_tcp_buffers.py "%LWIP_OPTS%" --tcp-source "%TCP_SOURCE%"
if errorlevel 1 exit /b 1
python scripts\e2e\patch_hev_tcp_buffers.py "%LWIP_OPTS%" --tcp-source "%TCP_SOURCE%" --check
if errorlevel 1 exit /b 1

findstr /S /I /C:"SPEEDVPN_UID_POC_BEGIN" /C:"native_set_uid_poc_callback" /C:"TProxySetUidPocCallback" /C:"uid_poc_" "%DIR%\src\*.c" "%DIR%\src\*.h" >nul
if not errorlevel 1 (
  echo ERROR - unsafe UID POC patch residue found in native sources
  exit /b 1
)
echo OK - native TCP patches verified; UID POC hook absent
