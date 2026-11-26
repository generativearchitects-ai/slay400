#!/bin/sh
# Downloads the slay400-core release archive that the documentation examples install as the tool slay400 (gateway kickoff
# 09): the release of the version the gateway is built with, slay400-core.version of the root pom.xml, so that the version
# is named in one place. Checks its SHA-256 and prints the path of the archive, for SLAY400_CORE_ARCHIVE.
#
# Usage: ci/core-archive.sh FOLDER
set -eu

folder="$1"
root="$(cd "$(dirname "$0")/.." && pwd)"
version="$(sed -n 's:.*<slay400-core.version>\(.*\)</slay400-core.version>.*:\1:p' "$root/pom.xml" | head -n 1)"
case "$version" in
  "" | *[!A-Za-z0-9.+-]*) echo "ci/core-archive.sh: no usable slay400-core.version in pom.xml" >&2; exit 1 ;;
esac
archive="slay400-$version.tar.gz"
mkdir -p "$folder"
curl -fsSL -o "$folder/$archive" "https://github.com/generativearchitects-ai/slay400-core/releases/download/v$version/$archive"
curl -fsSL -o "$folder/$archive.sha256" "https://github.com/generativearchitects-ai/slay400-core/releases/download/v$version/$archive.sha256"
(cd "$folder" && sha256sum -c "$archive.sha256" >&2)
echo "$folder/$archive"
