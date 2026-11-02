#!/usr/bin/env bash
# 배포 이미지 하나를 굽고 ghcr 에 올린다. sandcorpDeploy 의 「이미지 빌드」 단계가 macos 또는 home 노드에서
# 저장소 루트(체크아웃한 곳)에서 부른다. 이미지가 여럿이면 하나씩 부른다.
#
# 입력(환경변수)
#   IMAGE_REPO     ghcr.io/sandcorporation/<이름>               (필수)
#   IMAGE_SHA      태그로 쓸 커밋 sha 40자                        (필수)
#   BUILT_ON       macos | home (로그용)
#   DOCKERFILE     기본 Dockerfile        BUILD_CONTEXT  기본 .
#   PLATFORM       기본 linux/arm64       BUILDER_NAME   buildx 빌더 이름(docker-container 드라이버, 없으면 만든다)
#   SOURCE_URL     org.opencontainers.image.source 레이블
#   USE_SSH        1 이면 GIT_SSH_KEY 를 임시 ssh-agent 에 넣고 `--ssh default` 로 넘긴다(비공개 git 의존성)
#   CACHE_REF      빌드 캐시 레지스트리 주소. 비면 <repo>-buildcache:<arch>
#   GHCR_TOKEN     ghcr 쓰기 토큰. 노드의 docker 설정은 건드리지 않고 임시 DOCKER_CONFIG 에만 적는다
# 결과: <repo>:<sha> 한 태그. 빌드 캐시는 CACHE_REF, 기본 <repo>-buildcache:<arch>(레지스트리, mode=max) — 노드끼리 공유한다.
# bash 3.2(macos 노드)에서도 돈다. 토큰·키 경로·내용은 찍지 않는다.
set -euo pipefail

: "${IMAGE_REPO:?IMAGE_REPO 가 없다}"
: "${IMAGE_SHA:?IMAGE_SHA 가 없다}"
dockerfile="${DOCKERFILE:-Dockerfile}"
context="${BUILD_CONTEXT:-.}"
platform="${PLATFORM:-linux/arm64}"
builder="${BUILDER_NAME:-sandcorp-images}"
arch="${platform##*/}"
start="$(date +%s)"

tmp="$(mktemp -d "${TMPDIR:-/tmp}/sandcorp-docker.XXXXXX")"
cleanup() {
  rm -rf "$tmp"
  if [ -n "${SSH_AGENT_PID:-}" ]; then ssh-agent -k >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

if ! docker buildx version >/dev/null 2>&1; then
  echo "이 노드의 docker CLI 에 buildx 가 없다" >&2
  exit 1
fi

if [ -n "${GHCR_TOKEN:-}" ]; then
  orig="${DOCKER_CONFIG:-$HOME/.docker}"
  mkdir -p "$orig/buildx"
  for d in cli-plugins buildx contexts; do
    if [ -e "$orig/$d" ]; then ln -s "$orig/$d" "$tmp/$d"; fi
  done
  # auths 에 직접 적는다 — `docker login` 은 키체인(macos)·pass 를 찾다 잠겨서 실패한다.
  python3 - "$orig/config.json" "$tmp/config.json" <<'PY'
import base64, json, os, sys
src, dst = sys.argv[1:3]
try:
    with open(src) as f:
        cfg = json.load(f)
except (OSError, ValueError):
    cfg = {}
for key in ("auths", "credsStore", "credHelpers"):
    cfg.pop(key, None)
tok = os.environ["GHCR_TOKEN"].strip()
cfg["auths"] = {"ghcr.io": {"auth": base64.b64encode(f"sandcorp-ci:{tok}".encode()).decode()}}
fd = os.open(dst, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
with os.fdopen(fd, "w") as f:
    json.dump(cfg, f)
PY
  export DOCKER_CONFIG="$tmp"
else
  echo "GHCR_TOKEN 이 없다 — 이 노드의 docker 로그인을 그대로 쓴다"
fi

ssh_args=()
if [ "${USE_SSH:-0}" = 1 ]; then
  if [ -n "${GIT_SSH_KEY:-}" ]; then
    eval "$(ssh-agent -s)" >/dev/null
    ssh-add "$GIT_SSH_KEY" 2>/dev/null
  fi
  ssh_args=(--ssh default)
fi

if ! docker buildx inspect "$builder" >/dev/null 2>&1; then
  echo "빌더 $builder 를 만든다(docker-container)"
  docker buildx create --name "$builder" --driver docker-container >/dev/null
fi
docker buildx inspect --bootstrap "$builder" >/dev/null

labels=(--label "org.opencontainers.image.revision=$IMAGE_SHA")
if [ -n "${SOURCE_URL:-}" ]; then labels+=(--label "org.opencontainers.image.source=$SOURCE_URL"); fi

echo "빌드 노드 ${BUILT_ON:-?} · $platform · $IMAGE_REPO:$IMAGE_SHA ($dockerfile, 문맥 $context)"
cache="${CACHE_REF:-$IMAGE_REPO-buildcache:$arch}"
# bash 3.2 + set -u 에서 빈 배열 "${a[@]}" 는 unbound 오류다 — ${a[@]+...} 로 감싼다.
docker buildx build --builder "$builder" --platform "$platform" --provenance=false --push \
  ${ssh_args[@]+"${ssh_args[@]}"} \
  --file "$dockerfile" \
  --tag "$IMAGE_REPO:$IMAGE_SHA" \
  "${labels[@]}" \
  --cache-from "type=registry,ref=$cache" \
  --cache-to "type=registry,ref=$cache,mode=max" \
  "$context"
echo "이미지 빌드·push 완료 (${BUILT_ON:-?}): $IMAGE_REPO:$IMAGE_SHA $(( $(date +%s) - start ))초"
