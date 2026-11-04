#!/bin/bash
# AleArena -> GitHub hochladen (Doppelklick im Finder)
cd "$(dirname "$0")" || exit 1

USER_NAME="AleB-Roni"
REPO="AleArena"

if ! command -v git >/dev/null 2>&1; then
  echo "Git ist nicht installiert. Im Terminal 'xcode-select --install' ausführen."
  read -r -p "Enter zum Schließen"; exit 1
fi

[ -d .git ] || git init -b main
git add -A
git commit -m "AleArena Update $(date '+%Y-%m-%d %H:%M')" || echo "Keine neuen Änderungen."

if ! git remote get-url origin >/dev/null 2>&1; then
  if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    gh repo create "$USER_NAME/$REPO" --private --source . --remote origin
  else
    git remote add origin "https://github.com/$USER_NAME/$REPO.git"
    echo ""
    echo "Lege jetzt auf github.com ein LEERES Repo '$REPO' an (ohne README)."
    read -r -p "Enter, wenn fertig … "
  fi
fi

git branch -M main
if git push -u origin main; then
  echo ""
  echo "✅ Hochgeladen. Der Build läuft hier:"
  echo "   https://github.com/$USER_NAME/$REPO/actions"
  echo "   Fertige Jar: https://github.com/$USER_NAME/$REPO/releases/tag/latest"
  open "https://github.com/$USER_NAME/$REPO/actions" 2>/dev/null
else
  echo "❌ Push fehlgeschlagen – siehe Meldung oben."
fi
read -r -p "Enter zum Schließen"
