#!/usr/bin/env bash
set -euo pipefail

viewer_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
dist_dir=${EAPDF_VIEWER_DIST:-"${viewer_dir%/*}/dist"}
pom="$viewer_dir/pom.xml"
edition=$(<"$viewer_dir/edition")
version=$(sed -n 's/^[[:space:]]*<revision>\([^<]*\)<\/revision>[[:space:]]*$/\1/p' "$pom")

if [[ ! "$edition" =~ ^(full|lite|secure)$ || ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  printf 'Expected a known viewer edition and a numeric major.minor.patch revision.\n' >&2
  exit 1
fi

build_dir=$(mktemp -d "${TMPDIR:-/tmp}/eapdf-desktop-viewer.XXXXXX")
staged_jar=
cleanup() {
  rm -rf -- "$build_dir"
  if [[ -n "$staged_jar" ]]; then
    rm -f -- "$staged_jar"
  fi
}
trap cleanup EXIT

mvn -B -f "$pom" "$@" "-Drevision=$version" "-Deapdf.build.directory=$build_dir" package >&2
jar_name="eapdf-desktop-viewer-$edition-$version.jar"
mkdir -p -- "$dist_dir"
staged_jar=$(mktemp "$dist_dir/.$jar_name.XXXXXX")
install -m 0644 -- "$build_dir/eapdf-desktop-viewer-$version-standalone.jar" "$staged_jar"

exec 9<"$dist_dir"
flock 9
mv -fT -- "$staged_jar" "$dist_dir/$jar_name"
shopt -s nullglob
for previous in "$dist_dir"/eapdf-desktop-viewer-"$edition"-*.{jar,jar.sha256,jar.sha512,tar.gz,tar.gz.sha256,tar.gz.sha512}; do
  if [[ "$previous" != "$dist_dir/$jar_name" && -f "$previous" ]]; then
    rm -f -- "$previous"
  fi
done
printf '%s\n' "$dist_dir/$jar_name"
