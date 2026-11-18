@echo off
rem Builds EasyLog for Windows with its own name, icon and bundled Java runtime.
rem Run from the project root after "mvn clean package". Output: dist\EasyLog\EasyLog.exe (window)
rem and dist\EasyLog\EasyLogCLI.exe (same program with a console, for "analyze" in a terminal).
rem For an installer, change "app-image" to "msi" (requires the WiX Toolset).
cd /d "%~dp0\.."
if not exist target\EasyLog.jar (echo Build the JAR first: mvn clean package & exit /b 1)
if exist dist\EasyLog rmdir /s /q dist\EasyLog
if exist dist\stage rmdir /s /q dist\stage
mkdir dist\stage
copy target\EasyLog.jar dist\stage\ >nul
jpackage --type app-image --name EasyLog --app-version 2.1.0 ^
  --description "Finds, explains and pinpoints errors in application logs" --vendor "EasyLog" ^
  --icon packaging\EasyLog.ico --input dist\stage --main-jar EasyLog.jar ^
  --add-modules java.base,java.desktop,java.logging,java.sql ^
  --java-options "--enable-native-access=ALL-UNNAMED" --java-options "-Xmx1g" ^
  --add-launcher EasyLogCLI=packaging\windows-cli.properties --dest dist
rmdir /s /q dist\stage
echo Done. Output is in dist\EasyLog
