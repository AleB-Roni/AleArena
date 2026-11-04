@echo off
chcp 65001 >nul
rem AleArena -> GitHub hochladen (Doppelklick unter Windows)
cd /d "%~dp0"
set USER_NAME=AleB-Roni
set REPO=AleArena

where git >nul 2>nul || (echo Git fehlt: https://git-scm.com/download/win & pause & exit /b 1)

if not exist .git git init -b main
git add -A
git commit -m "AleArena Update %date% %time%" || echo Keine neuen Aenderungen.

git remote get-url origin >nul 2>nul
if errorlevel 1 (
  where gh >nul 2>nul && gh auth status >nul 2>nul && (
    gh repo create %USER_NAME%/%REPO% --private --source . --remote origin
  ) || (
    git remote add origin https://github.com/%USER_NAME%/%REPO%.git
    echo.
    echo Lege jetzt auf github.com ein LEERES Repo "%REPO%" an ^(ohne README^).
    pause
  )
)

git branch -M main
git push -u origin main && (
  echo.
  echo Hochgeladen. Build: https://github.com/%USER_NAME%/%REPO%/actions
  echo Fertige Jar:  https://github.com/%USER_NAME%/%REPO%/releases/tag/latest
  start "" https://github.com/%USER_NAME%/%REPO%/actions
) || echo Push fehlgeschlagen - siehe Meldung oben.
pause
