# jenkins-shared

Sandcorp Jenkins 공용 라이브러리(빌드·배포·자동 롤백). Jenkins 전역 라이브러리 이름 `sandcorp`, 기본 버전 `main`, 자격 `github-farrar142`.
전역 라이브러리라 스크립트 승인 제약이 없다(신뢰 코드) — 그래서 저장소 Jenkinsfile에는 호출 하나만 둔다.

```
vars/sandcorpDeploy.groovy        main 배포 파이프라인(선언형). 저장소 Jenkinsfile이 부르는 것
src/kr/sandcorp/ci/Nodes.groovy   노드 라벨·자격 ID·mac 대기 시간 — 이것들을 적는 유일한 곳
src/kr/sandcorp/ci/DeployGate.groovy  이 빌드가 배포할지(순수 함수)
resources/sandcorp/build-image.sh 빌드 노드에서 이미지 하나 굽기(buildx·레지스트리 캐시·임시 DOCKER_CONFIG·--ssh default)
resources/sandcorp/deploy.sh      oracle에서 pull→DB 확인→migrate→교체→healthy→헬스, 실패하면 자동 롤백
tests/                            Jenkins 없이 도는 시험(bash tests/run.sh)
```

## 저장소 Jenkinsfile 쓰는 법

```groovy
@Library('sandcorp') _

sandcorpDeploy(
  name: 'Sandcorp',                                   // 디스코드 말머리 [Sandcorp]
  image: 'ghcr.io/sandcorporation/portfolio',
  imageVar: 'PORTFOLIO_IMAGE',                        // compose 파일이 읽는 이미지 변수
  sourceUrl: 'https://github.com/sandcorporation/portfolio',
  buildSsh: true,
  check: 'bash scripts/ci/check.sh',
  composeProject: 'portfolio',
  composeFiles: ['docker-compose.prod.yml', 'docker-compose.oracle.yml'],
  dbCheck: 'python manage.py check --database default',
  migrate: ['python manage.py migrate --noinput', 'python manage.py createcachetable'],
  keepServices: ['renderer'],
  extraServices: ['worker'],
  healthCmd: 'python -c "..."',
)
```

라이브러리 브랜치를 시험하려면 `@Library('sandcorp@<브랜치>') _`(버전 덮어쓰기 허용). 병합 전에 `@Library('sandcorp') _`로 되돌린다.
잡은 지금처럼 「Pipeline script from SCM」, 브랜치 `*/main`, `Jenkinsfile`이다. 파라미터·트리거(`githubPush`)는 라이브러리가 정하고 첫 빌드 뒤 잡에 등록된다.

## 인자

| 인자 | 기본 | 뜻 |
|---|---|---|
| `name` | (필수) | 디스코드 말머리 `[name]` |
| `composeProject` | (필수) | `COMPOSE_PROJECT_NAME`. buildx 빌더 이름 기본값 `<composeProject>-images`에도 쓴다 |
| `image` + `imageVar` | — | 이미지 하나: ghcr 저장소와 compose가 읽는 변수 이름 |
| `images` | — | 이미지 여럿(daypage): `[[repo:, envVar:, dockerfile: 'Dockerfile', context: '.', platform:], ...]`. `image`와 둘 중 하나 |
| `platform` | `linux/arm64` | 모든 이미지의 기본 플랫폼(항목마다 덮어쓰기) |
| `sourceUrl` | 빈 값 | `org.opencontainers.image.source` 레이블 |
| `buildSsh` | `false` | 빌드 노드에서 `github-farrar142` 키를 임시 ssh-agent에 넣고 `--ssh default`(비공개 git 의존성) |
| `builder` | `<composeProject>-images` | buildx 빌더(docker-container) 이름 |
| `check` | 빈 값 | 배포 전 검사 명령. 빌드 노드에서 체크아웃 뒤·이미지 빌드 전에 돈다. 실패하면 굽지도 배포하지도 않는다. `GIT_SSH_KEY`·`GHCR_TOKEN`·`SANDCORP_BUILT_ON`이 있다. 검사가 이미지 빌드 안에서 끝나는 저장소는 비운다 |
| `branch` | `main` | 배포 브랜치 |
| `composeFiles` | `['docker-compose.prod.yml']` | `COMPOSE_FILE`(`:`로 잇는다) |
| `deployDir` | 잡 워크스페이스 | oracle에서 배포할 디렉터리(`ws()`). 운영 `.env`가 있어야 한다 |
| `service` | `web` | 주 서비스. DB 확인·migrate도 이 서비스 이미지로 `compose run --rm --no-deps` |
| `preServices` | `[]` | DB 확인 전에 `up -d --wait`할 서비스. compose에 정의돼 있을 때만(로컬 확인용 mysql 등) |
| `dbCheck` | 빈 값 | DB 접속 확인 명령(10번, 3초 간격). 비면 건너뜀 |
| `migrate` | `[]` | migrate 명령들(문자열 하나도 된다). 교체 전에, 옛 서비스가 도는 동안 돈다 |
| `keepServices` | `[]` | 주 서비스가 healthy 해진 뒤 `up -d --no-deps`만(고정 태그) |
| `extraServices` | `[]` | 주 서비스가 healthy 해진 뒤 새 이미지로 다시 만든다(worker·scheduler) |
| `healthCmd` | 빈 값 | 주 서비스 컨테이너 안에서 `sh -c`로 돌릴 헬스 명령(3번, 4초 간격) |
| `healthUrl` | 빈 값 | oracle에서 `curl -f`로 볼 주소. `healthCmd`와 둘 다 있으면 둘 다 본다 |
| `keepImages` | `3` | oracle에 저장소마다 남길 이미지 수 |
| `deployScript` | 빈 값 | 라이브러리 배포 대신 저장소 스크립트(daypage의 롤링 교체처럼 순서가 다를 때). 아래 「저장소 배포 스크립트」 |
| `macWaitSeconds` | `Nodes.MAC_WAIT_SECONDS`(180) | `MAC_WAIT_SECONDS` 파라미터 기본값 |
| `timeoutMinutes` | `90` | 빌드 전체 시간 제한 |

잡 파라미터: `BUILDER`(auto/macos/home), `MAC_WAIT_SECONDS`, `ROLLBACK_SHA`.

## 동작

1. **배포할 커밋 확인**(built-in): `checkout scm`으로 main sha를 얻는다. githubPush 트리거는 웹훅의 ref를 넘겨주지 않아서, 작업 브랜치 생성·push·삭제에도 잡이 깨고 늘 main을 받는다. 그래서 `kr.sandcorp.ci.DeployGate`가 이렇게 정한다.
   - main sha가 **직전 성공 빌드의 main sha**와 같으면 → `NOT_BUILT`, 디스코드 알림 없음(작업 브랜치 push, 배포 중에 쌓인 같은 sha 빌드).
   - push가 깨운 빌드인데 main sha가 **배포까지 갔다 실패한 가장 최근 빌드**의 sha와 같으면 → `NOT_BUILT`(깨진 커밋이 브랜치 push마다 배포·자동 롤백을 되풀이하지 않게). 「Build Now」는 다시 시도한다.
   - `ROLLBACK_SHA`가 있으면 언제나 배포.
2. **이미지 빌드**: `BUILDER=auto`면 `Nodes.MAC_BUILD`를 `MAC_WAIT_SECONDS` 기다리고, 못 잡으면 `Nodes.HOME_BUILD`. 넘어가는 건 노드를 못 잡았을 때뿐이다(실행기를 잡은 뒤의 실패는 실패). 그 노드에서 배포할 sha를 체크아웃 → `check` → 이미지마다 `build-image.sh`(`<repo>:<sha>` 한 태그, 캐시 `<repo>-buildcache:<arch>`).
3. **배포**(`Nodes.DEPLOY`): 배포할 sha를 체크아웃(compose 파일이 그 sha 것), `deploy.sh`: pull → `preServices` → `dbCheck` → `migrate` → 주 서비스 교체 → healthy 대기 → `keepServices`·`extraServices` → 헬스 확인 → 옛 이미지 정리.
4. **디스코드**(`discord-deploy-webhook`, URL은 찍지 않는다): ✅ 성공 · ❌ 실패 · ↩️ 자동 롤백(새 sha·되돌린 sha) · 🚨 자동 롤백도 실패 · 되돌리기 결과. 알림 실패는 빌드 결과를 바꾸지 않는다.

### 자동 롤백

주 서비스를 새 이미지로 바꾼 **뒤**(교체·healthy 대기·딸린 서비스·헬스 중 하나라도) 실패하면, **직전 성공 빌드가 올린 sha**(`DEPLOY_SHA`) 이미지로 주 서비스와 `extraServices`를 다시 바꾸고 healthy·헬스를 다시 본다.

| `deploy.sh` 종료 | 뜻 | 빌드 |
|---|---|---|
| 0 | 성공 | SUCCESS |
| 1 | 교체 **전** 실패(pull·DB·migrate) — 살아 있는 서비스는 옛 이미지 그대로 | FAILURE |
| 2 | 교체 뒤 실패, 직전 성공 이미지가 없다(첫 배포 등) | FAILURE |
| 3 | 교체 뒤 실패 → 직전 이미지로 되돌렸고 헬스 통과 | FAILURE + ↩️ 알림 |
| 4 | 되돌리기도 실패 — 운영이 내려가 있을 수 있다 | FAILURE + 🚨 알림 |

- **migrate는 되돌리지 않는다.** 옛 코드가 새 스키마와 호환돼야 한다(파괴적 migrate는 PR 검사가 막는다 — 별도 티켓).
- 자동 롤백 경로는 운영에서 일부러 깨뜨려 시험하지 않는다. `tests/deploy_test.sh`가 가짜 docker로 헬스 실패·unhealthy·롤백 실패·직전 없음·migrate 실패·pull 실패를 돌린다.
- 자동 롤백한 빌드는 FAILURE라 다음 빌드의 「직전 성공」은 여전히 되돌린 그 sha다.

### 수동 되돌리기

「Build with Parameters」에 `ROLLBACK_SHA=<sha 40자>`. 빌드 없이 그 sha 이미지를 같은 절차(migrate 포함 — 옛 코드의 migrate는 할 일이 없다)로 배포한다. 이미 배포한 sha여도 다시 배포하니, 같은 sha를 강제로 다시 배포할 때도 쓴다. compose 파일은 지금 main 것을 쓴다.

### 저장소 배포 스크립트(`deployScript`)

라이브러리 순서로 안 되는 저장소(daypage: backend 두 대 롤링·Caddy reload)는 `deployScript: 'deploy/deploy.sh'`. 라이브러리는 이미지 변수(`images[].envVar`)와 `COMPOSE_PROJECT_NAME`·`COMPOSE_FILE`·`DEPLOY_IMAGES`·`SANDCORP_ROLLBACK=0`을 넘겨 부른다. 0이 아니면 직전 성공 sha 이미지로 **같은 스크립트를 `SANDCORP_ROLLBACK=1`로 한 번 더** 부른다 — 스크립트는 그때 migrate를 건너뛰어야 한다. 단계를 구분하지 못하므로 교체 전 실패에도 되돌리기를 한 번 돈다(옛 이미지를 다시 올릴 뿐이다).

## 노드 라벨 바꾸는 곳

`src/kr/sandcorp/ci/Nodes.groovy` 하나뿐이다(`MAC_BUILD`·`HOME_BUILD`·`DEPLOY`·`CONTROLLER`, 자격 ID, mac 대기 기본값).
예: `laurio-staging` 라벨을 지우려면 먼저 `HOME_BUILD = 'built-in'`으로 바꿔 main에 병합한다 — 이 라이브러리를 쓰는 모든 저장소의 다음 빌드부터 바뀐다.

## 시험

```
bash tests/run.sh
```

도커만 있으면 된다: ① `tests/deploy_test.sh`(가짜 docker로 `deploy.sh` 성공·자동 롤백 경로) ② `tests/DeployGateTest.groovy`(배포할지 판단) ③ Groovy 전부 `groovyc` 컴파일 ④ shellcheck. GitHub Actions(`.github/workflows/test.yml`)가 PR마다 같은 것을 돌린다.
Jenkins DSL(`pipeline`·`node`·`sh`)을 흉내 내는 시험은 없다 — 그 부분은 `@Library('sandcorp@<브랜치>')`로 실제 잡에서 본다.
