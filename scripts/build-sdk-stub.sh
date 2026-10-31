#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
stub_root="$repo_root/ruoyi-union-sdk/sdk-stub"
lib_dir="$repo_root/ruoyi-union-sdk/lib"
mkdir -p -- "$lib_dir"

artifacts=(
  taobao-sdk-java-auto_1761200125504-20261031.jar
  taobao-sdk-java-auto_1761200125504-20261031-source.jar
  jd-api-sdk-java-20261031.jar
  pop-sdk-1.19.4-all.jar
  pop-sdk-1.19.4-all-sources.jar
)

missing=false
for artifact in "${artifacts[@]}"; do
  if [[ ! -e "$lib_dir/$artifact" && ! -L "$lib_dir/$artifact" ]]; then
    missing=true
  fi
done
if [[ "$missing" == false ]]; then
  printf '%s\n' 'All five SDK JARs already exist; leaving them unchanged.'
  exit 0
fi

javac_cmd=javac
jar_cmd=jar
if [[ -n "${JAVA_HOME:-}" ]]; then
  javac_cmd="$JAVA_HOME/bin/javac"
  jar_cmd="$JAVA_HOME/bin/jar"
fi
if ! command -v "$javac_cmd" >/dev/null || ! command -v "$jar_cmd" >/dev/null; then
  printf '%s\n' 'A full JDK 21 (javac and jar) is required; set JAVA_HOME or PATH.' >&2
  exit 1
fi

build_dir=$(mktemp -d "$lib_dir/.sdk-stub-build.XXXXXX")
trap 'rm -rf -- "$build_dir"' EXIT
cat > "$build_dir/manifest.mf" <<'MANIFEST'
Manifest-Version: 1.0
Implementation-Title: Development-only alliance SDK stub
Implementation-Version: STUB-NOT-FOR-PRODUCTION
SDK-Stub: true

MANIFEST

# mv -n also preserves a JAR placed here after the initial existence check.
publish_missing() {
  local built=$1 destination=$2
  if [[ -e "$destination" || -L "$destination" ]]; then
    printf 'Kept existing %s\n' "$(basename -- "$destination")"
  else
    mv -n -- "$built" "$destination"
    printf 'Prepared %s\n' "$(basename -- "$destination")"
  fi
}

build_family() {
  local family=$1 binary=$2 sources=${3:-}
  local source_dir="$stub_root/$family"
  local classes_dir="$build_dir/$family-classes"
  if [[ ! -e "$lib_dir/$binary" && ! -L "$lib_dir/$binary" ]]; then
    mkdir -p -- "$classes_dir"
    local -a sdk_sources=()
    while IFS= read -r -d '' source; do
      sdk_sources+=("$source")
    done < <(find "$source_dir" -type f -name '*.java' -print0)
    if [[ ${#sdk_sources[@]} -eq 0 ]]; then
      printf 'No SDK stub sources found: %s\n' "$source_dir" >&2
      exit 1
    fi
    "$javac_cmd" --release 21 -encoding UTF-8 -d "$classes_dir" "${sdk_sources[@]}"
    "$jar_cmd" --create --file "$build_dir/$binary" --manifest "$build_dir/manifest.mf" -C "$classes_dir" .
    publish_missing "$build_dir/$binary" "$lib_dir/$binary"
  else
    printf 'Kept existing %s\n' "$binary"
  fi
  if [[ -n "$sources" ]]; then
    if [[ ! -e "$lib_dir/$sources" && ! -L "$lib_dir/$sources" ]]; then
      "$jar_cmd" --create --file "$build_dir/$sources" --manifest "$build_dir/manifest.mf" -C "$source_dir" .
      publish_missing "$build_dir/$sources" "$lib_dir/$sources"
    else
      printf 'Kept existing %s\n' "$sources"
    fi
  fi
}

printf '%s\n' 'Building development-only SDK stubs. Real requests throw an explicit stub error.'
build_family pdd "${artifacts[3]}" "${artifacts[4]}"
