@ECHO OFF
SETLOCAL
SET APP_HOME=%~dp0
SET WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
SET WRAPPER_URL=https://services.gradle.org/distributions/gradle-8.11.1-wrapper.jar
SET WRAPPER_SHA256=2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046

IF NOT EXIST "%WRAPPER_JAR%" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $u='%WRAPPER_URL%'; $o='%WRAPPER_JAR%.tmp'; Invoke-WebRequest -UseBasicParsing -Uri $u -OutFile $o; $h=(Get-FileHash $o -Algorithm SHA256).Hash.ToLower(); if($h -ne '%WRAPPER_SHA256%'){Remove-Item $o -Force; throw 'Gradle wrapper JAR checksum mismatch'}; Move-Item -Force $o '%WRAPPER_JAR%'"
  IF ERRORLEVEL 1 EXIT /B 1
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "$h=(Get-FileHash '%WRAPPER_JAR%' -Algorithm SHA256).Hash.ToLower(); if($h -ne '%WRAPPER_SHA256%'){throw 'Gradle wrapper JAR checksum mismatch'}"
IF ERRORLEVEL 1 EXIT /B 1

IF DEFINED JAVA_HOME (
  SET JAVACMD=%JAVA_HOME%\bin\java.exe
) ELSE (
  SET JAVACMD=java.exe
)

"%JAVACMD%" -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
ENDLOCAL
