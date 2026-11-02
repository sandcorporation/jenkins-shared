#!/usr/bin/env bash
# resources/sandcorp/deploy.sh 를 가짜 docker(tests/fake-docker)로 돌려 성공·자동 롤백 경로를 확인한다.
#   bash tests/deploy_test.sh
# Jenkins·운영 없이 돈다. 헬스 실패 경로(자동 롤백)는 운영에서 일부러 깨뜨리지 않고 여기서 시험한다.
set -uo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
script="$root/resources/sandcorp/deploy.sh"
work="$(mktemp -d "${TMPDIR:-/tmp}/sandcorp-deploy-test.XXXXXX")"
mkdir -p "$work/bin"
cp "$root/tests/fake-docker" "$work/bin/docker"
chmod +x "$work/bin/docker"
pass=0
fail=0

# run <이름> <기대 종료코드> [VAR=값 ...] — 시나리오 하나를 새 워크스페이스에서 돌린다.
run() {
  local name=$1 want=$2
  shift 2
  local ws="$work/$name"
  mkdir -p "$ws"
  : > "$ws/.env"
  : > "$ws/log"
  : > "$ws/state"
  (
    cd "$ws" || exit 99
    env PATH="$work/bin:$PATH" FAKE_LOG="$ws/log" FAKE_STATE="$ws/state" FAKE_IMAGE_VAR=APP_IMAGE \
      COMPOSE_PROJECT_NAME=demo COMPOSE_FILE=docker-compose.prod.yml \
      DEPLOY_IMAGES="APP_IMAGE=ghcr.io/x/app:new" DEPLOY_PREVIOUS_IMAGES="APP_IMAGE=ghcr.io/x/app:old" \
      DEPLOY_DB_CHECK="python manage.py check --database default" \
      DEPLOY_MIGRATE=$'python manage.py migrate --noinput\npython manage.py createcachetable' \
      DEPLOY_EXTRA_SERVICES=worker DEPLOY_KEEP_SERVICES=renderer \
      DEPLOY_HEALTH_CMD="python -c 'print(1)'" DEPLOY_SLEEP=0 DEPLOY_HEALTHY_TRIES=3 \
      "$@" bash "$script" > "$ws/out" 2>&1
  )
  got=$?
  if [ "$got" = "$want" ]; then
    LAST="$ws"
    pass=$((pass + 1))
    return 0
  fi
  echo "FAIL $name: 종료코드 $got (기대 $want)"
  tail -n 20 "$ws/out"
  fail=$((fail + 1))
  LAST="$ws"
  return 1
}

# check <설명> <명령...> — 직전 시나리오에 대한 확인
check() {
  local what=$1
  shift
  if "$@"; then pass=$((pass + 1)); else echo "FAIL $what"; tail -n 20 "$LAST/out"; fail=$((fail + 1)); fi
}
last_web() { grep '^web=' "$LAST/state" | tail -n 1 | cut -d= -f2-; }
count() { grep -c -- "$1" "$LAST/log"; }

# 1) 성공: 순서는 pull → DB 확인 → migrate 둘 → web 교체 → renderer → worker → 헬스
if run success 0; then
  check "성공: web 이 새 이미지" test "$(last_web)" = "ghcr.io/x/app:new"
  check "성공: worker 도 새 이미지" grep -qx 'worker=ghcr.io/x/app:new' "$LAST/state"
  order=$(grep -n -E 'pull|check --database|migrate|createcachetable|up .*web$|up .*renderer$|up .*worker$|exec' "$LAST/log" | cut -d: -f1 | tr '\n' ' ')
  # shellcheck disable=SC2086 # 줄 번호 목록을 일부러 나눈다
  sorted=$(printf '%s\n' $order | sort -n | tr '\n' ' ')
  check "성공: 단계 순서" test "$order" = "$sorted"
  # shellcheck disable=SC2086
  check "성공: 단계 8개" test "$(printf '%s\n' $order | wc -l)" -eq 8
  check "성공: 옛 이미지는 pull 안 함" test "$(count 'pull ghcr.io/x/app:old')" -eq 0
fi

# 2) 헬스 실패 → 직전 이미지로 자동 롤백, 롤백 뒤 헬스 통과 → 3
if run rollback 3 FAKE_BAD_HEALTH=":new"; then
  check "롤백: web 이 옛 이미지" test "$(last_web)" = "ghcr.io/x/app:old"
  check "롤백: worker 도 옛 이미지" test "$(grep '^worker=' "$LAST/state" | tail -n 1)" = "worker=ghcr.io/x/app:old"
  check "롤백: migrate 는 한 번만(되돌리지 않음)" test "$(count 'migrate --noinput')" -eq 1
  check "롤백: 헬스를 다시 봤다" test "$(count ' exec ')" -ge 4
  check "롤백: 알림 문구" grep -q '자동 롤백 완료' "$LAST/out"
fi

# 3) healthy 가 안 됨 → 자동 롤백 → 3
if run unhealthy 3 FAKE_UNHEALTHY=":new"; then
  check "unhealthy: web 이 옛 이미지" test "$(last_web)" = "ghcr.io/x/app:old"
fi

# 4) 롤백한 옛 이미지도 헬스 실패 → 4
run rollback-broken 4 FAKE_BAD_HEALTH="ghcr.io/x/app" && check "롤백 실패: 문구" grep -q '자동 롤백도 실패' "$LAST/out"

# 5) 되돌릴 이미지가 없다 → 2
run no-previous 2 FAKE_BAD_HEALTH=":new" DEPLOY_PREVIOUS_IMAGES= && check "직전 없음: 교체는 했다" test "$(last_web)" = "ghcr.io/x/app:new"

# 6) migrate 실패 → 교체 전이라 1, web 을 건드리지 않는다
if run migrate-fail 1 FAKE_MIGRATE_FAIL=1; then
  check "migrate 실패: web 교체 없음" test -z "$(last_web)"
fi

# 7) pull 실패 → 1, 아무것도 안 띄움
if run pull-fail 1 FAKE_PULL_FAIL=":new"; then
  check "pull 실패: 아무 서비스도 안 띄움" test ! -s "$LAST/state"
fi

# 8) 롤백 이미지 pull 실패 → 4
run rollback-pull-fail 4 FAKE_BAD_HEALTH=":new" FAKE_PULL_FAIL=":old" || true

# 9) 이미지 여럿(daypage 처럼): 둘 다 export 되고 둘 다 pull
if run two-images 0 DEPLOY_IMAGES="APP_IMAGE=ghcr.io/x/app:new DIST_IMAGE=ghcr.io/x/dist:new"; then
  check "이미지 둘: 둘 다 pull" test "$(count 'pull ghcr.io/x/')" -eq 2
fi

# 10) .env 가 없으면 바로 1
mkdir -p "$work/no-env"
( cd "$work/no-env" && env PATH="$work/bin:$PATH" FAKE_LOG=/dev/null FAKE_STATE=/dev/null COMPOSE_PROJECT_NAME=demo DEPLOY_IMAGES=A=b bash "$script" >/dev/null 2>&1 )
if [ $? = 1 ]; then pass=$((pass + 1)); else echo "FAIL no-env"; fail=$((fail + 1)); fi

echo "deploy.sh 시험: 통과 $pass, 실패 $fail"
[ "$fail" = 0 ]
