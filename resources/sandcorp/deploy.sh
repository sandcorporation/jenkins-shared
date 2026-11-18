#!/usr/bin/env bash
# sandcorpDeploy 의 「배포」 단계가 oracle 노드의 잡 워크스페이스(./.env 가 있는 곳)에서 부른다.
#   pull → (DB 앞 서비스 기동) → DB 확인 → 별도 컨테이너 migrate → 주 서비스 교체 → healthy 대기
#   → 딸린 서비스 교체 → 헬스 확인. 교체 뒤에 실패하면 직전 이미지로 자동으로 되돌리고 헬스를 다시 본다.
# migrate 는 되돌리지 않는다(옛 코드가 새 스키마와 호환돼야 한다 — 파괴적 migrate 는 PR 검사가 막는다).
#
# 종료 코드
#   0 배포 성공
#   1 교체 전에 실패(pull·DB·migrate) — 살아 있는 서비스는 옛 이미지 그대로
#   2 교체 뒤 실패, 되돌릴 이미지가 없다(DEPLOY_PREVIOUS_IMAGES 비어 있음)
#   3 교체 뒤 실패 → 직전 이미지로 되돌렸고 헬스도 통과
#   4 교체 뒤 실패 → 되돌리기도 실패(운영이 내려가 있을 수 있다)
#
# 입력(환경변수). 목록은 공백·줄바꿈으로 나눈다.
#   COMPOSE_PROJECT_NAME, COMPOSE_FILE   docker compose 가 그대로 읽는다
#   DEPLOY_IMAGES            "VAR=이미지 ..." 이번에 올릴 이미지. VAR 은 compose 파일이 읽는 변수(PORTFOLIO_IMAGE 등)
#   DEPLOY_PREVIOUS_IMAGES   같은 모양. 자동 롤백 대상(직전 성공 배포). 비면 자동 롤백 없음
#   DEPLOY_SERVICE           주 서비스(기본 web). migrate·DB 확인도 이 서비스 이미지로 돈다
#   DEPLOY_PRE_SERVICES      DB 확인 전에 `up -d --wait` 할 서비스. compose 에 정의돼 있을 때만(로컬 mysql 등)
#   DEPLOY_DB_CHECK          DB 확인 명령(주 서비스 컨테이너 안). 비면 건너뜀
#   DEPLOY_DB_TRIES          DB 확인 시도 횟수(기본 10, 3초 간격)
#   DEPLOY_MIGRATE           줄마다 명령 하나. 각각 `compose run --rm --no-deps <주 서비스> sh -c <명령>`
#   DEPLOY_EXTRA_SERVICES    주 서비스가 healthy 해진 뒤 새 이미지로 다시 만드는 서비스(worker 등)
#   DEPLOY_KEEP_SERVICES     `up -d --no-deps` 만(고정 태그 서비스, 설정이 같으면 그대로)
#                            PRE·KEEP 서비스는 떠 있는 컨테이너와 image 문자열만 다르고 같은 이미지(ID)·같은 정의면
#                            up 을 건너뛴다(태그 → 태그@sha256 고정이 db 를 다시 만들지 않게, same_image_only_ref_changed)
#   DEPLOY_HEALTH_CMD        주 서비스 컨테이너 안에서 돌릴 헬스 명령(sh -c)
#   DEPLOY_HEALTH_URL        배포 노드에서 curl 로 볼 주소(둘 다 있으면 둘 다 본다)
#   DEPLOY_HEALTH_TRIES      헬스 확인 시도 횟수(기본 3, DEPLOY_SLEEP*2 초 간격)
#   DEPLOY_HEALTHY_TRIES     healthy 대기 횟수(기본 120, 1초 간격 — healthcheck start_interval 2s 를 놓치지 않게)
#   DEPLOY_SLEEP             대기 간격 초(기본 2, 시험에서 0)
#   DEPLOY_KEEP_IMAGES       저장소마다 남길 이미지 수(기본 3)
#   DEPLOY_ENV_FILE          워크스페이스 밖 env 파일(Jenkins 노드 환경변수로 준다 — 저장소에 경로를 적지 않는다).
#                            있으면 ./.env 를 그 파일을 가리키는 링크로 다시 만든다. 체크아웃 실패로 워크스페이스가
#                            지워져도 비밀값이 사라지지 않는다. 읽을 수 없으면 교체 전에 1
#   DEPLOY_REQUIRED_ENV      .env 에 빈 값이 아니어야 할 키(공백 구분, sandcorpDeploy requiredEnv). 하나라도 없으면 교체 전에 1
#                            (compose 기본값으로 조용히 떨어지는 것 — 바인드 주소 등 — 을 막는다). 값은 찍지 않는다
set -euo pipefail

: "${COMPOSE_PROJECT_NAME:?COMPOSE_PROJECT_NAME 이 없다}"
: "${DEPLOY_IMAGES:?DEPLOY_IMAGES 가 없다}"
svc="${DEPLOY_SERVICE:-web}"
nap="${DEPLOY_SLEEP:-2}"
# healthy 대기는 1초(DEPLOY_SLEEP 이 더 작으면 그것). 2초면 start_interval 2s 와 엇갈려 한 번씩 늦게 본다.
healthy_nap=$(( nap < 1 ? nap : 1 ))

if [ -n "${DEPLOY_ENV_FILE:-}" ]; then
  if [ ! -f "$DEPLOY_ENV_FILE" ] || [ ! -r "$DEPLOY_ENV_FILE" ]; then
    echo "DEPLOY_ENV_FILE(노드 환경변수)이 가리키는 env 파일을 읽을 수 없다 — 교체 전에 멈춘다" >&2
    exit 1
  fi
  ln -sfn "$DEPLOY_ENV_FILE" .env
  echo "== .env → 노드의 DEPLOY_ENV_FILE"
fi

if [ ! -f .env ]; then
  echo "워크스페이스에 .env가 없다. .env.example을 보고 만들어 두거나 노드에 DEPLOY_ENV_FILE 을 준다." >&2
  exit 1
fi

# .env 에서 KEY 의 값(마지막 정의, 따옴표 벗김). 값은 비었는지만 본다.
env_value() {
  sed -n -E "s/^[[:space:]]*(export[[:space:]]+)?$1[[:space:]]*=[[:space:]]*//p" .env | tail -n 1 |
    sed -E "s/[[:space:]]+$//; s/^\"(.*)\"$/\1/; s/^'(.*)'$/\1/"
}
missing=""
for key in ${DEPLOY_REQUIRED_ENV:-}; do
  case "$key" in [A-Za-z_]*) ;; *) echo "DEPLOY_REQUIRED_ENV 키 모양이 틀렸다: $key" >&2; exit 1 ;; esac
  [ -n "$(env_value "$key")" ] || missing="$missing $key"
done
if [ -n "$missing" ]; then
  echo ".env 에 값이 없는 필수 키:$missing — 교체 전에 멈춘다(살아 있는 서비스는 그대로)" >&2
  exit 1
fi

compose() { docker compose "$@"; }

# 떠 있는 <svc> 컨테이너가 compose 의 image 문자열과 다른 문자열(예 태그 → 태그@sha256)로 만들어졌지만 같은 이미지(ID)를
# 가리키고, image 를 컨테이너 문자열로 되돌린 정의 해시가 라벨과 같으면(= 다른 정의는 그대로) 0.
# compose 는 정의 해시에 image 문자열이 들어가 이때도 up 이 컨테이너를 다시 만든다(compose v5.7 실측) — db 같은
# 상태 있는 서비스가 재생성되지 않게 건너뛴다. 원하는 이미지가 로컬에 없거나(digest 를 올린 PR) 다르면 1 → up.
same_image_only_ref_changed() {
  local s=$1 cid cur_ref want_ref want_id ov hash label
  [ -n "${COMPOSE_FILE:-}" ] || return 1 # override 를 붙일 파일 목록이 없으면 판정하지 않는다
  cid=$(compose ps -q --status running "$s" 2>/dev/null | head -n 1) || return 1
  [ -n "$cid" ] || return 1
  cur_ref=$(docker inspect -f '{{.Config.Image}}' "$cid" 2>/dev/null) || return 1
  want_ref=$(compose config --images "$s" 2>/dev/null) || return 1
  [ -n "$cur_ref" ] || return 1
  [ -n "$want_ref" ] || return 1
  [ "$cur_ref" != "$want_ref" ] || return 1 # 같은 문자열이면 up 이 스스로 no-op 을 판단한다
  want_id=$(docker image inspect -f '{{.Id}}' "$want_ref" 2>/dev/null) || return 1
  [ -n "$want_id" ] || return 1
  [ "$want_id" = "$(docker inspect -f '{{.Image}}' "$cid" 2>/dev/null)" ] || return 1
  ov=$(mktemp "${TMPDIR:-/tmp}/sandcorp-deploy-ov.XXXXXX") || return 1
  printf 'services:\n  %s:\n    image: "%s"\n' "$s" "$cur_ref" > "$ov"
  hash=$(COMPOSE_FILE="${COMPOSE_FILE}${COMPOSE_PATH_SEPARATOR:-:}$ov" compose config --hash "$s" 2>/dev/null | awk '{print $2}') || hash=""
  rm -f "$ov"
  label=$(docker inspect -f '{{index .Config.Labels "com.docker.compose.config-hash"}}' "$cid" 2>/dev/null) || return 1
  [ -n "$hash" ] && [ "$hash" = "$label" ]
}

# 기반 서비스(DEPLOY_PRE_SERVICES·DEPLOY_KEEP_SERVICES)를 올리는 곳은 모두 여기를 거친다.
#   up_base <svc> <up 인자...>
up_base() {
  local s=$1
  shift
  if same_image_only_ref_changed "$s"; then
    echo "$s 유지 — image 문자열만 바뀌고 같은 이미지(재생성 안 함)"
    return 0
  fi
  compose up "$@" "$s"
}

# "VAR=이미지 ..." 를 export 한다.
use_images() {
  local pair
  for pair in $1; do
    case "$pair" in *=*) ;; *) echo "이미지 목록 모양이 틀렸다: $pair" >&2; return 1 ;; esac
    export "${pair%%=*}=${pair#*=}"
  done
}

pull_images() {
  local pair
  for pair in $1; do
    echo "== 이미지 pull (${pair#*=})"
    docker pull "${pair#*=}"
  done
}

wait_healthy() {
  local i id status
  for i in $(seq 1 "${DEPLOY_HEALTHY_TRIES:-120}"); do
    id=$(compose ps -q "$svc" || true)
    status=$(docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null || echo starting)
    if [ "$status" = "healthy" ]; then
      echo "$svc healthy (${i}회째)"
      return 0
    fi
    sleep "$healthy_nap"
  done
  echo "$svc가 healthy가 되지 않았다" >&2
  compose logs --tail 80 "$svc" >&2 || true
  return 1
}

health_once() {
  if [ -n "${DEPLOY_HEALTH_CMD:-}" ]; then
    compose exec -T "$svc" sh -c "$DEPLOY_HEALTH_CMD" || return 1
  fi
  if [ -n "${DEPLOY_HEALTH_URL:-}" ]; then
    curl -fsS --max-time 10 "$DEPLOY_HEALTH_URL" || return 1
    echo
  fi
}

health_check() {
  local i
  echo "== 헬스 확인"
  for i in $(seq 1 "${DEPLOY_HEALTH_TRIES:-3}"); do
    if health_once; then
      echo "헬스 통과 (${i}회째)"
      return 0
    fi
    sleep "$((nap * 2))"
  done
  echo "헬스 확인 실패" >&2
  return 1
}

# 주 서비스 교체 → healthy → 딸린 서비스 → 헬스. 롤백도 같은 길을 migrate 없이 간다.
switch_and_check() {
  local s
  echo "== $svc 교체"
  # --force-recreate: 새 이미지라도 compose가 "Running"으로 두고 넘어가는 경우가 있었다.
  compose up -d --no-deps --no-build --force-recreate "$svc" || return 1
  wait_healthy || return 1
  for s in ${DEPLOY_KEEP_SERVICES:-}; do
    up_base "$s" -d --no-deps || return 1
  done
  for s in ${DEPLOY_EXTRA_SERVICES:-}; do
    compose up -d --no-deps --no-build --force-recreate "$s" || return 1
  done
  health_check
}

# ---------- 교체 전: 실패해도 살아 있는 서비스는 그대로다 ----------
use_images "$DEPLOY_IMAGES"
pull_images "$DEPLOY_IMAGES" || exit 1

if [ -n "${DEPLOY_PRE_SERVICES:-}" ]; then
  defined=$(compose config --services)
  for s in $DEPLOY_PRE_SERVICES; do
    if printf '%s\n' "$defined" | grep -qx "$s"; then
      up_base "$s" -d --wait || exit 1
    fi
  done
fi

if [ -n "${DEPLOY_DB_CHECK:-}" ]; then
  echo "== DB 접속 확인"
  db_ok=0
  for i in $(seq 1 "${DEPLOY_DB_TRIES:-10}"); do
    if compose run --rm --no-deps "$svc" sh -c "$DEPLOY_DB_CHECK" >/dev/null 2>&1; then
      echo "DB 접속됨 (${i}회째)"
      db_ok=1
      break
    fi
    sleep "$((nap + 1))"
  done
  if [ "$db_ok" != 1 ]; then
    echo "DB에 닿지 않는다. 마지막 시도 출력:" >&2
    compose run --rm --no-deps "$svc" sh -c "$DEPLOY_DB_CHECK" >&2 || true
    exit 1
  fi
fi

if [ -n "${DEPLOY_MIGRATE:-}" ]; then
  echo "== migrate"
  while IFS= read -r cmd; do
    [ -n "${cmd// /}" ] || continue
    compose run --rm --no-deps "$svc" sh -c "$cmd" || exit 1
  done <<< "$DEPLOY_MIGRATE"
fi

# ---------- 교체 뒤: 실패하면 직전 이미지로 되돌린다 ----------
if switch_and_check; then
  compose ps || true
  keep="${DEPLOY_KEEP_IMAGES:-3}"
  for pair in $DEPLOY_IMAGES; do
    img="${pair#*=}"
    # pull 한 옛 sha 이미지는 dangling 이 아니라 prune 에 안 걸린다 — 최신 몇 개만 남긴다(롤백은 다시 pull).
    docker image ls --format '{{.Repository}}:{{.Tag}}' "${img%:*}" | tail -n +"$((keep + 1))" | xargs -r docker rmi >/dev/null 2>&1 || true
  done
  docker image prune -f --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME" >/dev/null 2>&1 || true
  echo "== 배포 성공"
  exit 0
fi

echo "== 새 이미지로 교체한 뒤 실패했다" >&2
if [ -z "${DEPLOY_PREVIOUS_IMAGES:-}" ]; then
  echo "되돌릴 직전 이미지가 없다(DEPLOY_PREVIOUS_IMAGES 비어 있음) — 자동 롤백 못 함" >&2
  exit 2
fi

echo "== 자동 롤백: $DEPLOY_PREVIOUS_IMAGES (migrate 는 되돌리지 않는다)" >&2
if use_images "$DEPLOY_PREVIOUS_IMAGES" && pull_images "$DEPLOY_PREVIOUS_IMAGES" && switch_and_check; then
  compose ps || true
  echo "== 자동 롤백 완료 — 직전 이미지로 헬스 통과" >&2
  exit 3
fi
echo "== 자동 롤백도 실패했다 — 운영을 바로 봐야 한다" >&2
exit 4
