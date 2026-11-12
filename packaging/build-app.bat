@echo off
rem Builds LogReader for Windows with its own name, icon and bundled Java runtime.
rem Run from the project root after "mvn clean package". Output: dist\LogReader\LogReader.exe (window)
rem and dist\LogReader\LogReaderCLI.exe (same program with a console, for "analyze" in a terminal).
rem For an installer, change "app-image" to "msi" (requires the WiX Toolset).
cd /d "%~dp0\.."
if not exist target\LogReader.jar (echo Build the JAR first: mvn clean package & exit /b 1)
if exist dist\LogReader rmdir /s /q dist\LogReader
if exist dist\stage rmdir /s /q dist\stage
mkdir dist\stage
copy target\LogReader.jar dist\stage\ >nul
jpackage --type app-image --name LogReader --app-version 1.0.0 ^
  --description "Finds, explains and pinpoints errors in application logs" --vendor "LogReader" ^
  --icon packaging\LogReader.ico --input dist\stage --main-jar LogReader.jar ^
  --add-modules java.base,java.desktop,java.logging,java.sql ^
  --java-options "--enable-native-access=ALL-UNNAMED" --java-options "-Xmx1g" ^
  --add-launcher LogReaderCLI=packaging\windows-cli.properties --dest dist
rmdir /s /q dist\stage
echo Done. Output is in dist\LogReader
