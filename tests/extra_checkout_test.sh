#!/usr/bin/env bash
# resources/sandcorp/extra-checkout.sh 를 로컬 git 저장소(file://)로 돌린다:
#   고정 sha 받기(얕게·HEAD 검증) / 저장소에 없는 sha / 이미 있는 dir / 잘못된 sha·dir / 키는 있는데 known_hosts 없음.
#   bash tests/extra_checkout_test.sh
set -uo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
script="$root/resources/sandcorp/extra-checkout.sh"
work="$(mktemp -d "${TMPDIR:-/tmp}/sandcorp-extra-test.XXXXXX")"
pass=0
fail=0

# 데이터 저장소 흉내: 커밋 둘(앞 것을 고정한다 — 얕은 받기가 최신이 아니라 그 sha 를 받는지 본다).
src="$work/src"
git init -q -b main "$src"
git -C "$src" -c user.name=t -c user.email=t@t commit -q --allow-empty -m one
printf 'a\n' > "$src/a.txt"
git -C "$src" add a.txt
git -C "$src" -c user.name=t -c user.email=t@t commit -q -m two
printf 'b\n' > "$src/b.txt"
git -C "$src" add b.txt
git -C "$src" -c user.name=t -c user.email=t@t commit -q -m three
pinned=$(git -C "$src" rev-parse HEAD~1)
url="file://$src"

# check <이름> <기대 종료코드> <명령...>  (작업 디렉터리마다 새로)
check() {
  local name=$1 want=$2
  shift 2
  mkdir -p "$work/ws-$name"
  (cd "$work/ws-$name" && "$@") > "$work/$name.out" 2>&1
  local rc=$?
  if [ "$rc" -eq "$want" ]; then pass=$((pass + 1)); else fail=$((fail + 1)); echo "실패: $name 종료 $rc(기대 $want)"; cat "$work/$name.out"; fi
}
expect() {
  local what=$1
  shift
  if "$@"; then pass=$((pass + 1)); else fail=$((fail + 1)); echo "실패: $what"; fi
}

check ok 0 bash "$script" data "$url" "$pinned"
expect '받은 HEAD = 고정 sha' [ "$(git -C "$work/ws-ok/data" rev-parse HEAD)" = "$pinned" ]
expect '고정 sha 의 내용(a.txt 있음)' [ -f "$work/ws-ok/data/a.txt" ]
expect '고정 sha 의 내용(뒤 커밋의 b.txt 없음)' [ ! -e "$work/ws-ok/data/b.txt" ]
expect '얕게 받음(커밋 1개)' [ "$(git -C "$work/ws-ok/data" rev-list --count HEAD)" = 1 ]

check missing-sha 128 bash "$script" data "$url" "$(printf '%040d' 0 | tr 0 a)"
mkdir -p "$work/ws-exists/data"
check exists 2 bash "$script" data "$url" "$pinned"
check short-sha 2 bash "$script" data "$url" "${pinned:0:12}"
check upper-sha 2 bash "$script" data "$url" "$(printf '%s' "$pinned" | tr a-f A-F)"
check dir-dotdot 2 bash "$script" .. "$url" "$pinned"
check dir-slash 2 bash "$script" a/b "$url" "$pinned"
check key-without-known-hosts 2 env SANDCORP_EXTRA_KEY=/nonexistent bash "$script" data "$url" "$pinned"
check args 2 bash "$script" data "$url"

echo "extra_checkout_test: 통과 $pass · 실패 $fail"
[ "$fail" -eq 0 ]
