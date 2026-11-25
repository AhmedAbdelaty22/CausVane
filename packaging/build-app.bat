@echo off
rem Builds CausVane for Windows: the app with its own name, icon and bundled Java, or its installer.
rem GitHub's build runs it on its Windows machine for every release (.github/workflows/build.yml);
rem it is not meant to be run on your own computer.
rem
rem   packaging\build-app.bat        the app on its own: dist\CausVane\CausVane.exe
rem   packaging\build-app.bat exe    the installer: dist\CausVane-<version>.exe (needs the WiX Toolset 3)
rem
rem The installer installs for the current user without administrator rights, lets the user choose the
rem folder, adds CausVane to the Start menu and the desktop, appears under "Installed apps" for
rem uninstalling, and replaces an older version when a newer one is installed.
cd /d "%~dp0\.."
set TYPE=%1
if "%TYPE%"=="" set TYPE=app-image
if not exist target\CausVane.jar (echo Build the JAR first ^(Maven package phase^) & exit /b 1)
set EXTRA=
if /i "%TYPE%"=="exe" set EXTRA=--win-menu --win-menu-group CausVane --win-shortcut --win-dir-chooser --win-per-user-install --win-upgrade-uuid 68ee8a84-4b72-4fa1-9aca-7d67a569d87f --about-url https://github.com/AhmedAbdelaty22/CausVane
if /i "%TYPE%"=="msi" set EXTRA=--win-menu --win-menu-group CausVane --win-shortcut --win-dir-chooser --win-per-user-install --win-upgrade-uuid 68ee8a84-4b72-4fa1-9aca-7d67a569d87f --about-url https://github.com/AhmedAbdelaty22/CausVane
if /i "%TYPE%"=="app-image" if exist dist\CausVane rmdir /s /q dist\CausVane
if exist dist\stage rmdir /s /q dist\stage
mkdir dist\stage
copy target\CausVane.jar dist\stage\ >nul
jpackage --type %TYPE% --name CausVane --app-version 2.3.0 ^
  --description "Finds, explains and pinpoints errors in application logs" --vendor "CausVane" ^
  --copyright "CausVane contributors" ^
  --icon packaging\CausVane.ico --input dist\stage --main-jar CausVane.jar ^
  --add-modules java.base,java.desktop,java.logging,java.sql ^
  --java-options "--enable-native-access=ALL-UNNAMED" --java-options "-Xmx1g" %EXTRA% --dest dist
if errorlevel 1 exit /b 1
rmdir /s /q dist\stage
echo Done. Output is in dist
