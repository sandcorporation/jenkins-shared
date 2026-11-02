#!/usr/bin/env bash
# 이 저장소의 시험 전부. Jenkins 없이 돈다(도커만 있으면 된다).
#   bash tests/run.sh
#   1) deploy.sh 성공·자동 롤백 경로 · build-image.sh 인자(가짜 docker)   2) DeployGate·PrCheckSkip 시험(groovy)
#   3) Groovy 전부 컴파일(문법 검사)                  4) shellcheck
#   5) 파괴적 migrate 판정(python:3.9 — 맥 노드 python3 호환) · 새 migration 고르기(git)
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
GROOVY_IMAGE="${GROOVY_IMAGE:-groovy:4.0.27-jdk17}"
SHELLCHECK_IMAGE="${SHELLCHECK_IMAGE:-koalaman/shellcheck:v0.10.0}"
PYTHON_IMAGE="${PYTHON_IMAGE:-python:3.9-slim}"

echo "== 1) deploy.sh · build-image.sh"
bash tests/deploy_test.sh
bash tests/build_image_test.sh

echo "== 2) DeployGate·PrCheckSkip · 3) Groovy 컴파일"
docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -v "$root:/src:ro" -w /src "$GROOVY_IMAGE" sh -c '
  set -e
  out=$(mktemp -d)
  groovyc -d "$out" src/kr/sandcorp/ci/*.groovy
  for f in vars/*.groovy; do groovyc -cp "$out" -d "$out" "$f"; done
  echo "컴파일 통과: $(ls src/kr/sandcorp/ci/*.groovy vars/*.groovy | wc -l)개"
  groovy -cp "$out" tests/DeployGateTest.groovy
  groovy -cp "$out" tests/PrCheckSkipTest.groovy
'

echo "== 4) shellcheck"
docker run --rm -v "$root:/src:ro" -w /src "$SHELLCHECK_IMAGE" -x resources/sandcorp/*.sh tests/*.sh tests/fake-docker
echo "== 5) 파괴적 migrate"
docker run --rm -u "$(id -u):$(id -g)" -v "$root:/src:ro" -w /src "$PYTHON_IMAGE" python -I tests/migrate_guard_test.py
bash tests/migrate_guard_sh_test.sh
echo "== 전부 통과"
