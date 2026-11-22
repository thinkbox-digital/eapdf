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
staged_inventory=
cleanup() {
  rm -rf -- "$build_dir"
  if [[ -n "$staged_jar" ]]; then
    rm -f -- "$staged_jar"
  fi
  if [[ -n "$staged_inventory" ]]; then
    rm -f -- "$staged_inventory"
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
inventory="$dist_dir/.eapdf-desktop-viewer-$edition.inventory"
destination="$dist_dir/$jar_name"
previous_name=
previous_hash=
if [[ -e "$inventory" || -L "$inventory" ]]; then
  if [[ ! -f "$inventory" || -L "$inventory" ]] ||
      ! read -r previous_hash previous_name < "$inventory" ||
      [[ ! "$previous_hash" =~ ^[0-9a-f]{64}$ ||
         ! "$previous_name" =~ ^eapdf-desktop-viewer-$edition-[0-9]+\.[0-9]+\.[0-9]+\.jar$ ]]; then
    printf 'Refusing invalid viewer inventory %s\n' "$inventory" >&2
    exit 1
  fi
fi

digest() {
  sha256sum < "$1" | cut -d ' ' -f 1
}

new_hash=$(digest "$staged_jar")
staged_inventory=$(mktemp "$dist_dir/.eapdf-desktop-viewer-inventory.XXXXXX")
printf '%s %s\n' "$new_hash" "$jar_name" > "$staged_inventory"

if [[ ! -e "$destination" && ! -L "$destination" ]]; then
  ln -T -- "$staged_jar" "$destination"
elif [[ ! -f "$destination" || -L "$destination" ]]; then
  printf 'Refusing to replace existing viewer path %s\n' "$destination" >&2
  exit 1
else
  current_hash=$(digest "$destination")
  if [[ "$current_hash" != "$new_hash" ]]; then
    if [[ "$previous_name" != "$jar_name" || "$current_hash" != "$previous_hash" ]]; then
      printf 'Refusing to replace a viewer JAR with unknown or changed contents %s\n' "$destination" >&2
      exit 1
    fi
    mv -fT -- "$staged_jar" "$destination"
  fi
fi
mv -fT -- "$staged_inventory" "$inventory"

if [[ -n "$previous_name" && "$previous_name" != "$jar_name" ]]; then
  previous="$dist_dir/$previous_name"
  if [[ -f "$previous" && ! -L "$previous" && "$(digest "$previous")" == "$previous_hash" ]]; then
    rm -- "$previous"
  fi
fi
printf '%s\n' "$destination"
