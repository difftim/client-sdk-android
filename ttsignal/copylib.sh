#!/usr/bin/env bash
set -euo pipefail

# Copy libsignal.so for Android ABIs from a ttsignal tarball into this module's jniLibs.
#
# Usage:
#   ./copylib.sh /path/to/ttsignal.tar.bz2
#
# The script will:
# - extract the tarball into a temp directory
# - locate libsignal.so for arm64-v8a and armeabi-v7a
# - copy them into: ./src/main/jniLibs/<abi>/libsignal.so

if [[ ${1:-} == "" || ${1:-} == "-h" || ${1:-} == "--help" ]]; then
  cat <<'EOF'
Usage:
  copylib.sh /path/to/ttsignal.tar.bz2

Copies libsignal.so for:
  - arm64-v8a
  - armeabi-v7a

into:
  ./src/main/jniLibs/<abi>/libsignal.so
EOF
  exit 0
fi

archive_path="$1"

if [[ ! -f "$archive_path" ]]; then
  echo "error: archive not found: $archive_path" >&2
  exit 1
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dest_root="$script_dir/src/main/jniLibs"

if [[ ! -d "$dest_root" ]]; then
  echo "error: destination jniLibs directory not found: $dest_root" >&2
  exit 1
fi

tmp_dir="$(mktemp -d)"
cleanup() {
  rm -rf "$tmp_dir" || true
}
trap cleanup EXIT

# macOS tar supports -j for bzip2; GNU tar supports it too.
# Use -xjf to extract .tar.bz2.
if ! tar -xjf "$archive_path" -C "$tmp_dir"; then
  echo "error: failed to extract archive: $archive_path" >&2
  exit 1
fi

# Find libsignal.so under an ABI folder in the extracted content.
# We accept common layouts, e.g.:
#   .../arm64-v8a/libsignal.so
#   .../jniLibs/arm64-v8a/libsignal.so
#   .../lib/arm64-v8a/libsignal.so
find_so_for_abi() {
  local abi="$1"

  local matches
  # shellcheck disable=SC2207
  matches=(
    $(find "$tmp_dir" -type f -name "libsignal.so" \
      -path "*/$abi/*" 2>/dev/null || true)
  )

  if [[ ${#matches[@]} -eq 0 ]]; then
    return 1
  fi

  # Prefer the shortest path (usually the most direct ABI folder match)
  local best="${matches[0]}"
  for candidate in "${matches[@]}"; do
    if [[ ${#candidate} -lt ${#best} ]]; then
      best="$candidate"
    fi
  done

  printf '%s' "$best"
}

copy_one() {
  local abi="$1"
  local dest_dir="$dest_root/$abi"
  local dest_file="$dest_dir/libsignal.so"

  mkdir -p "$dest_dir"

  local src
  if ! src="$(find_so_for_abi "$abi")"; then
    echo "error: could not find libsignal.so for ABI '$abi' inside: $archive_path" >&2
    echo "hint: expected something like */$abi/libsignal.so" >&2
    exit 1
  fi

  cp -f "$src" "$dest_file"
  echo "copied: $src -> $dest_file"
}

copy_one "arm64-v8a"
copy_one "armeabi-v7a"

cleanup
trap - EXIT

echo "done."
