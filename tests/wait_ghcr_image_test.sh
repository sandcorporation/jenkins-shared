#!/usr/bin/env bash
# resources/sandcorp/wait-ghcr-image.sh 를 가짜 docker 로 돌린다: N번째 확인에 나타남 / 끝내 없음(시간 초과) / IMAGE 없음.
#   bash tests/wait_ghcr_image_test.sh
set -uo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
script="$root/resources/sandcorp/wait-ghcr-image.sh"
work="$(mktemp -d "${TMPDIR:-/tmp}/sandcorp-wait-test.XXXXXX")"
mkdir -p "$work/bin"
# 가짜 docker: `manifest inspect` 를 부를 때마다 횟수를 세고, FAKE_APPEAR_AT 번째부터 성공한다(0이면 끝내 없음).
cat > "$work/bin/docker" <<'SH'
#!/usr/bin/env bash
echo "docker $*" >> "$FAKE_LOG"
n=$(( $(wc -l < "$FAKE_LOG") ))
if [ "${FAKE_APPEAR_AT:-0}" -gt 0 ] && [ "$n" -ge "$FAKE_APPEAR_AT" ]; then echo '{}'; exit 0; fi
echo "manifest unknown" >&2
exit 1
SH
chmod +x "$work/bin/docker"
pass=0
fail=0

# check <이름> <기대 종료코드> [VAR=값 ...]
check() {
  local name=$1 want=$2
  shift 2
  : > "$work/$name.log"
  env PATH="$work/bin:$PATH" FAKE_LOG="$work/$name.log" WAIT_INTERVAL_SECONDS=0 "$@" bash "$script" > "$work/$name.out" 2>&1
  local rc=$?
  if [ "$rc" -eq "$want" ]; then pass=$((pass + 1)); else echo "FAIL $name: 종료코드 $rc (기대 $want)"; cat "$work/$name.out"; fail=$((fail + 1)); fi
}
# out_has <이름> <글자>
out_has() {
  if grep -qF -- "$2" "$work/$1.out"; then pass=$((pass + 1)); else echo "FAIL $1: 출력에 '$2' 없음"; cat "$work/$1.out"; fail=$((fail + 1)); fi
}

check appears 0 IMAGE=ghcr.io/x/app:abc FAKE_APPEAR_AT=3
out_has appears '이미지 있음: ghcr.io/x/app:abc'
out_has appears '3번째 확인'
if grep -qxF 'docker manifest inspect ghcr.io/x/app:abc' "$work/appears.log"; then pass=$((pass + 1)); else echo "FAIL appears: manifest inspect 인자"; fail=$((fail + 1)); fi

check timeout 1 IMAGE=ghcr.io/x/app:abc FAKE_APPEAR_AT=0 WAIT_TIMEOUT_SECONDS=0 WAIT_HINT=https://example.invalid/actions
out_has timeout '0초 안에 레지스트리에 나타나지 않았다'
out_has timeout 'manifest unknown'
out_has timeout 'https://example.invalid/actions'

check noimage 2

echo "wait-ghcr-image.sh: 통과 $pass, 실패 $fail"
[ "$fail" -eq 0 ]
