#!/usr/bin/env bash
# resources/sandcorp/build-image.sh 를 가짜 docker(tests/fake-docker)로 돌려 buildx 인자를 확인한다.
#   bash tests/build_image_test.sh
# 캐시 주소 기본값(<repo>-buildcache:<arch>)과 CACHE_REF 덮어쓰기(sandcorp-flow 의 <repo>:buildcache), --ssh 를 본다.
set -uo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
script="$root/resources/sandcorp/build-image.sh"
work="$(mktemp -d "${TMPDIR:-/tmp}/sandcorp-build-test.XXXXXX")"
mkdir -p "$work/bin"
cp "$root/tests/fake-docker" "$work/bin/docker"
chmod +x "$work/bin/docker"
pass=0
fail=0

# run <이름> [VAR=값 ...] — 한 번 굽고 buildx build 줄을 $work/<이름>.build 에 남긴다.
run() {
  local name=$1
  shift
  : > "$work/$name.log"
  if ! env -u GHCR_TOKEN -u GIT_SSH_KEY PATH="$work/bin:$PATH" FAKE_LOG="$work/$name.log" FAKE_STATE="$work/$name.state" \
      IMAGE_REPO=ghcr.io/x/app IMAGE_SHA=abc123 BUILT_ON=home "$@" bash "$script" > "$work/$name.out" 2>&1; then
    echo "FAIL $name: build-image.sh 가 실패했다"
    tail -n 20 "$work/$name.out"
    fail=$((fail + 1))
    return 1
  fi
  grep 'buildx build' "$work/$name.log" > "$work/$name.build"
}

# has <이름> <설명> <글자> / lacks — buildx build 줄에 그 글자가 있는가
has() {
  if grep -qF -- "$3" "$work/$1.build"; then pass=$((pass + 1)); else echo "FAIL $1: $2 — '$3' 없음"; cat "$work/$1.build"; fail=$((fail + 1)); fi
}
lacks() {
  if grep -qF -- "$3" "$work/$1.build"; then echo "FAIL $1: $2 — '$3' 있음"; cat "$work/$1.build"; fail=$((fail + 1)); else pass=$((pass + 1)); fi
}

run default
has default '태그는 sha 하나' '--tag ghcr.io/x/app:abc123'
has default '캐시 기본값' '--cache-from type=registry,ref=ghcr.io/x/app-buildcache:arm64'
has default '캐시 쓰기 기본값' '--cache-to type=registry,ref=ghcr.io/x/app-buildcache:arm64,mode=max'
lacks default 'USE_SSH 없으면 ssh 없음' '--ssh'

run amd64 PLATFORM=linux/amd64 CACHE_REF=
has amd64 '빈 CACHE_REF 는 기본값' '--cache-from type=registry,ref=ghcr.io/x/app-buildcache:amd64'

run custom CACHE_REF=ghcr.io/x/app:buildcache USE_SSH=1 DOCKERFILE=backend/Dockerfile BUILD_CONTEXT=backend
has custom 'CACHE_REF 읽기' '--cache-from type=registry,ref=ghcr.io/x/app:buildcache'
has custom 'CACHE_REF 쓰기' '--cache-to type=registry,ref=ghcr.io/x/app:buildcache,mode=max'
lacks custom 'CACHE_REF 면 기본 캐시 안 씀' 'app-buildcache'
has custom 'ssh' '--ssh default'
has custom 'Dockerfile' '--file backend/Dockerfile'

echo "build-image.sh: 통과 $pass · 실패 $fail"
[ "$fail" = 0 ]
