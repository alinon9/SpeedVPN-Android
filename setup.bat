@echo off
cd /d "%~dp0"
if not exist app\src\main\jni\hev-socks5-tunnel\.git (
  git clone --recursive https://github.com/heiher/hev-socks5-tunnel app\src\main\jni\hev-socks5-tunnel
)
echo OK - now open this folder in Android Studio.
pause
