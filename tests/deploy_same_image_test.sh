#!/usr/bin/env bash
# deploy.sh 를 진짜 docker compose 로 돌려, 기반 서비스(db)의 image 문자열만 태그 → 태그@digest 로 바뀌면
# 컨테이너를 다시 만들지 않고, 다른 정의(환경변수)가 바뀌면 다시 만드는지 본다.
#   bash tests/deploy_same_image_test.sh
# docker pull 만 가짜(아무것도 안 함)로 바꾸고 나머지는 진짜 docker 다. 이미지는 alpine:3.21 하나(로컬에 없으면 받는다).
set -uo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
script="$root/resources/sandcorp/deploy.sh"
base_image="${SAME_IMAGE_TEST_IMAGE:-alpine:3.21}"
work="$(mktemp -d "${TMPDIR:-/tmp}/sandcorp-same-image-test.XXXXXX")"
project="sandcorp-same-image-$$"
app_image="sandcorp-deploy-it/app:$$" # 전용 저장소 이름 — deploy.sh 의 옛 이미지 정리가 남의 이미지를 건드리지 않게
pass=0
fail=0

docker image inspect "$base_image" >/dev/null 2>&1 || docker pull -q "$base_image" >/dev/null || {
  echo "deploy_same_image_test: $base_image 를 받을 수 없다" >&2
  exit 1
}
repo_digest=$(docker image inspect -f '{{index .RepoDigests 0}}' "$base_image")
pinned="$base_image@${repo_digest#*@}" # alpine:3.21@sha256:...
docker tag "$base_image" "$app_image"

cleanup() {
  (cd "$work" && COMPOSE_PROJECT_NAME="$project" COMPOSE_FILE=compose.yml DB_IMAGE="$base_image" DB_MODE=x \
    APP_IMAGE="$app_image" docker compose down -t 0 >/dev/null 2>&1)
  docker rmi "$app_image" >/dev/null 2>&1 || true
}
trap cleanup EXIT

mkdir -p "$work/bin"
real_docker=$(command -v docker)
# shellcheck disable=SC2016 # $1·$@ 는 만들어질 래퍼 스크립트 안에서 펼쳐진다
printf '#!/usr/bin/env bash\n[ "$1" = pull ] && exit 0\nexec %q "$@"\n' "$real_docker" > "$work/bin/docker"
chmod +x "$work/bin/docker"
: > "$work/.env"
cat > "$work/compose.yml" <<'EOF'
services:
  db:
    image: ${DB_IMAGE}
    command: ["sleep", "infinity"]
    init: true
    environment:
      MODE: ${DB_MODE}
  web:
    image: ${APP_IMAGE}
    command: ["sleep", "infinity"]
    init: true
    healthcheck:
      test: ["CMD", "true"]
      interval: 1s
      start_period: 5s
      start_interval: 1s
EOF

# deploy <DB_IMAGE> <DB_MODE> — deploy.sh 를 한 번 돌리고 출력은 $work/out
deploy() {
  (cd "$work" && env PATH="$work/bin:$PATH" COMPOSE_PROJECT_NAME="$project" COMPOSE_FILE=compose.yml \
    DB_IMAGE="$1" DB_MODE="$2" DEPLOY_IMAGES="APP_IMAGE=$app_image" DEPLOY_SERVICE=web \
    DEPLOY_PRE_SERVICES=db DEPLOY_SLEEP=1 DEPLOY_HEALTHY_TRIES=30 bash "$script" > "$work/out" 2>&1)
}
db_state() { docker inspect -f '{{.Id}} {{.Created}}' "$(cd "$work" && COMPOSE_PROJECT_NAME="$project" COMPOSE_FILE=compose.yml DB_IMAGE=x DB_MODE=x APP_IMAGE=x docker compose ps -q db)"; }
check() {
  local what=$1
  shift
  if "$@"; then pass=$((pass + 1)); else echo "FAIL $what"; tail -n 20 "$work/out"; fail=$((fail + 1)); fi
}

check "처음 배포(태그)" deploy "$base_image" a
first=$(db_state)
check "태그 → 태그@digest 배포" deploy "$pinned" a
check "같은 이미지: db 유지 로그" grep -q 'db 유지 — image 문자열만 바뀌고 같은 이미지(재생성 안 함)' "$work/out"
check "같은 이미지: db 컨테이너·Created 그대로" test "$(db_state)" = "$first"
check "다른 정의(환경변수) 배포" deploy "$pinned" b
check "다른 정의: 유지 로그 없음" bash -c "! grep -q 'db 유지' '$work/out'"
check "다른 정의: db 다시 만듦" test "$(db_state)" != "$first"

echo "deploy.sh 진짜 docker 시험($(docker compose version --short)): 통과 $pass, 실패 $fail"
[ "$fail" = 0 ]
