#!/usr/bin/env bash
# extraCheckouts 항목 하나를 고정 sha 로 얕게 받는다(sandcorpDeploy 배포 단계, README 「다른 저장소 받기」).
#   bash extra-checkout.sh <dir> <url> <sha>
# 환경:
#   SANDCORP_EXTRA_KEY     ssh 개인 키 파일(withCredentials sshUserPrivateKey 가 만든 임시 파일). 없으면 기본 ssh(시험).
#   SANDCORP_KNOWN_HOSTS   고정 known_hosts 파일(GitHub 공개 호스트 키). 키가 있으면 필수.
# <dir> 은 지금 디렉터리 바로 아래 한 칸, 이미 있으면 실패한다(지우기는 라이브러리가 Jenkins deleteDir 로 먼저 한다).
# 받은 HEAD 가 <sha> 가 아니면 실패한다. 키 내용은 찍지 않는다(-x 를 켜지 않는다).
set -euo pipefail
if [ $# -ne 3 ]; then
  echo "쓰는 법: extra-checkout.sh <dir> <url> <sha>" >&2
  exit 2
fi
dir=$1 url=$2 sha=$3
if ! [[ $sha =~ ^[0-9a-f]{40}$ ]]; then
  echo "extra-checkout: sha 가 40자 소문자 hex 가 아니다" >&2
  exit 2
fi
if ! [[ $dir =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]*$ ]]; then
  echo "extra-checkout: dir 은 한 칸 이름이어야 한다: $dir" >&2
  exit 2
fi
if [ -e "$dir" ] || [ -L "$dir" ]; then
  echo "extra-checkout: $dir 가 이미 있다(먼저 지워야 한다)" >&2
  exit 2
fi
if [ -n "${SANDCORP_EXTRA_KEY:-}" ]; then
  if [ ! -s "${SANDCORP_KNOWN_HOSTS:-}" ]; then
    echo "extra-checkout: SANDCORP_KNOWN_HOSTS 가 없다" >&2
    exit 2
  fi
  # 호스트 키는 고정 파일로만 확인한다(accept-new 는 첫 접속을 그대로 믿는다 — 매 배포 워크스페이스가 새로우면 늘 첫 접속).
  export GIT_SSH_COMMAND="ssh -i '$SANDCORP_EXTRA_KEY' -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes -o UserKnownHostsFile='$SANDCORP_KNOWN_HOSTS' -o GlobalKnownHostsFile=/dev/null"
fi
echo "== 다른 저장소 받기: $url @ ${sha:0:12} → $dir"
git init -q "$dir"
git -C "$dir" remote add origin "$url"
git -C "$dir" -c protocol.version=2 fetch -q --depth 1 --no-tags origin "$sha"
git -C "$dir" -c advice.detachedHead=false checkout -q --detach FETCH_HEAD
got=$(git -C "$dir" rev-parse HEAD)
if [ "$got" != "$sha" ]; then
  echo "extra-checkout: 받은 HEAD($got)가 고정 sha($sha)가 아니다" >&2
  exit 1
fi
echo "== 받음: $dir @ ${sha:0:12} ($(git -C "$dir" ls-files | wc -l | tr -d ' ')개 파일)"
