#!/usr/bin/env bash
# 다른 곳(GitHub Actions 등)에서 굽는 이미지가 레지스트리에 나타날 때까지 기다린다. vars/waitForGhcrImage.groovy 가 부른다.
#
#   IMAGE=ghcr.io/<org>/<repo>:<sha> bash wait-ghcr-image.sh
#
# 환경변수: IMAGE(필수), WAIT_TIMEOUT_SECONDS(기본 1800), WAIT_INTERVAL_SECONDS(기본 10),
#           WAIT_HINT(시간이 다 됐을 때 덧붙일 한 줄 — 예: Actions 실행 목록 주소)
# 자격: 이 노드의 docker 로그인을 그대로 쓴다(배포 노드의 pull 자격). 토큰을 받거나 찍지 않는다.
# 종료: 0 나타남 / 1 시간 초과 / 2 쓰는 법
set -uo pipefail

image="${IMAGE:-}"
if [ -z "$image" ]; then
  echo "쓰는 법: IMAGE=<저장소>:<태그> $0" >&2
  exit 2
fi
timeout_s="${WAIT_TIMEOUT_SECONDS:-1800}"
interval_s="${WAIT_INTERVAL_SECONDS:-10}"
start="$(date +%s)"
last_report=-60
tries=0

echo "== 이미지 기다리기: $image (상한 ${timeout_s}초, ${interval_s}초 간격)"
while :; do
  tries=$((tries + 1))
  if err="$(docker manifest inspect "$image" 2>&1 >/dev/null)"; then
    echo "== 이미지 있음: $image ($(( $(date +%s) - start ))초, ${tries}번째 확인)"
    exit 0
  fi
  elapsed=$(( $(date +%s) - start ))
  if [ "$elapsed" -ge "$timeout_s" ]; then
    echo "이미지가 ${timeout_s}초 안에 레지스트리에 나타나지 않았다: $image" >&2
    echo "마지막 응답: $(printf '%s' "$err" | head -n 1)" >&2
    echo "이미지를 굽는 쪽(GitHub Actions 이미지 워크플로)이 실패했거나 아직 돌고 있다. 그쪽 실행 결과를 보라." >&2
    if [ -n "${WAIT_HINT:-}" ]; then echo "$WAIT_HINT" >&2; fi
    exit 1
  fi
  # 1분에 한 줄만 남긴다(콘솔이 길어지지 않게).
  if [ $((elapsed - last_report)) -ge 60 ]; then
    echo "아직 없다(${elapsed}초): $(printf '%s' "$err" | head -n 1)"
    last_report=$elapsed
  fi
  sleep "$interval_s"
done
