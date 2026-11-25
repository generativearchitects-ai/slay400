#!/usr/bin/env bash
# Proves that nothing the build produces for others contains JTOpen (JT400), decision 143, gateway kickoff 10: not the
# distribution folder, not the two archives, not any layer of the image. A file is JTOpen when its name says so (jt400,
# jtopen) or when it is a jar with classes of com/ibm/as400; bom.json must list no component net.sf.jt400. The notices and
# lib/ext/README name JTOpen on purpose, to tell the operator where to get it; they are text, not JTOpen.
#
# Usage: ci/no-jt400.sh <distribution folder> <archive>... [--image <image>]
set -euo pipefail

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
FOUND=0

found() {
  echo "JTOpen found: $*" >&2
  FOUND=1
}

# Every jar under a folder: no entry of com/ibm/as400, no name of JTOpen.
scan_tree() {
  local root=$1 label=$2 names jars=0
  names=$(cd "$root" && find . \( -iname '*jt400*' -o -iname '*jtopen*' \) -print)
  [ -z "$names" ] || found "$label: files named $names"
  while IFS= read -r -d '' jar; do
    jars=$((jars + 1))
    if unzip -Z1 "$jar" 2>/dev/null | grep -q '^com/ibm/as400/'; then
      found "$label: ${jar#"$root"/} holds classes of com/ibm/as400"
    fi
  done < <(find "$root" -type f \( -name '*.jar' -o -name '*.zip' \) -print0)
  if [ -f "$root/bom.json" ] && grep -q '"pkg:maven/net.sf.jt400/' "$root/bom.json"; then
    found "$label: bom.json lists net.sf.jt400"
  fi
  echo "$label: $jars jars looked into, $(du -sk "$root" | cut -f1) KiB"
}

DIST=$1
shift
scan_tree "$DIST" "distribution folder"

IMAGE=""
while [ $# -gt 0 ]; do
  case $1 in
    --image) IMAGE=$2; shift 2 ;;
    *)
      archive=$1; shift
      out="$WORK/archive-$(basename "$archive")"
      mkdir -p "$out"
      case $archive in
        *.tar.gz) tar -xzf "$archive" -C "$out" ;;
        *.zip) unzip -q "$archive" -d "$out" ;;
      esac
      scan_tree "$out" "$(basename "$archive") ($(du -k "$archive" | cut -f1) KiB)"
      ;;
  esac
done

if [ -n "$IMAGE" ]; then
  mkdir -p "$WORK/image"
  docker save "$IMAGE" | tar -x -C "$WORK/image"
  layers=0
  # Each layer on its own, so that a file removed by a later layer is still seen in the layer that added it.
  while IFS= read -r -d '' blob; do
    if tar -tf "$blob" >/dev/null 2>&1; then opener=(tar -xf); elif tar -tzf "$blob" >/dev/null 2>&1; then opener=(tar -xzf); else continue; fi
    layers=$((layers + 1))
    root="$WORK/layer-$layers"
    mkdir -p "$root"
    "${opener[@]}" "$blob" -C "$root" 2>/dev/null || true
    scan_tree "$root" "image layer $layers" >/dev/null
  done < <(find "$WORK/image" -type f \( -name 'layer.tar' -o -path '*/blobs/sha256/*' \) -print0)
  echo "image $IMAGE: $layers layers looked into, $(docker image inspect -f '{{.Size}}' "$IMAGE") bytes"
fi

if [ "$FOUND" -ne 0 ]; then
  echo "FAILED: JTOpen is in what the build produces" >&2
  exit 1
fi
echo "No JTOpen in the distribution${IMAGE:+, the archives and the image}"
