@echo off
REM ── Compilation de l'APK SMAP, sans ouvrir Android Studio ──────────────
REM
REM JAVA_HOME est imposé ici volontairement : le Java installé sur la machine
REM est en version 26, que Gradle 8.9 ne supporte pas. On utilise le JDK 21
REM livré avec Android Studio, celui qu'attend le projet.

setlocal
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

REM %~dp0 se termine toujours par un antislash. Laissé tel quel, il échappe le
REM guillemet fermant et Gradle rejette le chemin ("Invalid file path").
set "PROJET=%~dp0"
if "%PROJET:~-1%"=="\" set "PROJET=%PROJET:~0,-1%"

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo ERREUR : JDK introuvable dans %JAVA_HOME%
  echo Verifiez le chemin d'installation d'Android Studio.
  exit /b 1
)

call "%PROJET%\gradlew.bat" -p "%PROJET%" assembleDebug %*
if errorlevel 1 (
  echo.
  echo ECHEC de la compilation - voir les erreurs ci-dessus.
  exit /b 1
)

echo.
echo APK genere :
echo   %PROJET%\app\build\outputs\apk\debug\app-debug.apk
endlocal
