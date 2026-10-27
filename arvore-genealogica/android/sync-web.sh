#!/bin/sh
# Copia o app web (a pasta acima) para dentro dos assets do projeto Android.
# Rode antes de compilar: sh sync-web.sh
set -eu
here=$(cd "$(dirname "$0")" && pwd)
src="$here/.."
dest="$here/app/src/main/assets/www"
rm -rf "$dest"
mkdir -p "$dest"
cp -r "$src/index.html" "$src/manifest.webmanifest" "$src/css" "$src/js" "$src/vendor" "$src/icons" "$dest/"
echo "App web copiado para $dest"
