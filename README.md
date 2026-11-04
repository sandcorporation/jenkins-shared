# jenkins-shared

Sandcorp Jenkins 공용 라이브러리(빌드·배포·자동 롤백). Jenkins 전역 라이브러리 이름 `sandcorp`, 기본 버전 `main`, 자격 `github-farrar142`.
전역 라이브러리라 스크립트 승인 제약이 없다(신뢰 코드) — 그래서 저장소 Jenkinsfile에는 호출 하나만 둔다.

```
vars/sandcorpDeploy.groovy        main 배포 파이프라인(선언형). 저장소 Jenkinsfile이 부르는 것
vars/sandcorpPrCheck.groovy       PR 검사 파이프라인. 저장소 Jenkinsfile.pr이 부르는 것(멀티브랜치 잡)
vars/sandcorpBuildNode.groovy     빌드 노드 고르기(먼저 노드를 기다리고 못 잡으면 다른 노드) — 배포(macos 먼저)·PR 검사(home 먼저)가 같이 쓴다
src/kr/sandcorp/ci/Nodes.groovy   노드 라벨·노드 순서·자격 ID·대기 시간 — 이것들을 적는 유일한 곳
src/kr/sandcorp/ci/DeployGate.groovy  이 빌드가 배포할지(순수 함수)
src/kr/sandcorp/ci/DeployStatus.groovy  배포 결과 커밋 상태(sandcorp/deploy)에 쓸 값(순수 함수)
resources/sandcorp/build-image.sh 빌드 노드에서 이미지 하나 굽기(buildx·레지스트리 캐시(CACHE_REF)·임시 DOCKER_CONFIG·--ssh default)
resources/sandcorp/migrate-guard.sh  PR이 새로 더한 Django migration을 골라 migrate-guard.py에 넘긴다
resources/sandcorp/migrate-guard.py  파괴적 migration 판정(표준 라이브러리만, python 3.8+)
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
| `images` | — | 이미지 여럿(daypage·sandcorp-flow): `[[repo:, envVar:, dockerfile: 'Dockerfile', context: '.', platform:, cache:], ...]`. `image`와 둘 중 하나. `cache`는 빌드 캐시 레지스트리 주소(기본 `<repo>-buildcache:<arch>`) — 전환 전 캐시를 그대로 쓰려는 저장소만 준다 |
| `platform` | `linux/arm64` | 모든 이미지의 기본 플랫폼(항목마다 덮어쓰기) |
| `sourceUrl` | 빈 값 | `org.opencontainers.image.source` 레이블 |
| `buildSsh` | `false` | 빌드 노드에서 `github-farrar142` 키를 임시 ssh-agent에 넣고 `--ssh default`(비공개 git 의존성) |
| `builder` | `<composeProject>-images` | buildx 빌더(docker-container) 이름 |
| `check` | 빈 값 | 배포 전 검사 명령. 빌드 노드에서 체크아웃 뒤 이미지 빌드와 나란히 돈다. 실패하면 빌드를 끊고 배포하지 않는다(ghcr에 `<repo>:<sha>`가 먼저 올라가 있을 수는 있다). PR 검사가 같은 트리를 통과시켰으면 건너뛴다(아래 「재검사 생략」). `GIT_SSH_KEY`·`GHCR_TOKEN`·`SANDCORP_BUILT_ON`이 있다. 검사가 이미지 빌드 안에서 끝나는 저장소는 비운다 |
| `skipCheckedTree` | `true` | `false`면 PR 검사 결과와 상관없이 늘 재검사 |
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
   - 배포하기로 했고 `check`가 있으면 **재검사 생략**을 판정한다(아래).
2. **이미지 빌드**: `BUILDER=auto`면 `Nodes.MAC_BUILD`를 `MAC_WAIT_SECONDS` 기다리고, 못 잡으면 `Nodes.HOME_BUILD`. 넘어가는 건 노드를 못 잡았을 때뿐이다(실행기를 잡은 뒤의 실패는 실패). 그 노드에서 배포할 sha를 체크아웃 → `check`와 이미지마다 `build-image.sh`(`<repo>:<sha>` 한 태그, 캐시 `<repo>-buildcache:<arch>`)를 나란히(`failFast`). 재검사를 생략하면 `검사` 단계에 근거 한 줄만 찍고 굽기만 한다.
3. **배포**(`Nodes.DEPLOY`): 배포할 sha를 체크아웃(compose 파일이 그 sha 것), `deploy.sh`: pull → `preServices` → `dbCheck` → `migrate` → 주 서비스 교체 → healthy 대기 → `keepServices`·`extraServices` → 헬스 확인 → 옛 이미지 정리.
4. **디스코드**(`discord-deploy-webhook`, URL은 찍지 않는다): ✅ 성공 · ❌ 실패 · ↩️ 자동 롤백(새 sha·되돌린 sha) · 🚨 자동 롤백도 실패 · 되돌리기 결과. 알림 실패는 빌드 결과를 바꾸지 않는다.
5. **GitHub 커밋 상태**(built-in): main 커밋에 `sandcorp/deploy`를 단다 — gate에서 `pending`, post에서 `success`/`failure`. 실패하면 병합된 PR에 실패 댓글(아래 「배포 결과(GitHub)」).

### 배포 결과(GitHub)

작업자(work)는 Jenkins API 자격이 없다. 그래서 배포 결과를 GitHub에 남기고 `wait-deploy.py`(issue-track 스킬)가 `gh`로 읽는다(`kr.sandcorp.ci.DeployStatus`).

- **커밋 상태** `sandcorp/deploy`, 배포한 main sha에. gate가 배포하기로 하면 `pending`(`빌드 #N · 배포 중`), post always가 `success`/`failure`(`빌드 #N · 성공 · 검사 생략 · 빌드 macos · 2분 3초`, `빌드 #N · 검사 실패 · 빌드 home`, `… 실패 → <sha> 자동 롤백` 등, 140자 안). 중단·시간 초과도 `failure`(`중단(시간 초과 포함)`) — pending으로 남기지 않는다. `target_url`은 비운다(빌드 URL은 내부 주소).
- **건너뛴 빌드**(NOT_BUILT): 그 main sha에 끝난 상태가 아직 없을 때만 `success`(`건너뜀 · 이미 배포한 sha`) 또는 `failure`(`건너뜀 · 직전 배포가 실패한 sha`). 이미 있으면 덮지 않는다(작업 브랜치 push마다 깨는 빌드가 기록을 지우지 않게). 배포 브랜치가 아닌 빌드는 남기지 않는다.
- **`ROLLBACK_SHA` 되돌리기**는 상태·댓글을 남기지 않는다(옛 sha의 기록을 덮지 않게).
- **실패 댓글**: `검사`·`이미지 굽기`·`배포`(첫 실행) 명령을 `sh -xe` 그대로 돌리되 `tee`로 로그를 남기고, 실패하면 배포한 sha를 만든 병합 PR(`commits/<sha>/pulls`, 재검사 판정이 이미 찾았으면 그 PR)에 댓글 하나 — 제목 `배포 실패 — <단계> · <sha 12자> · <노드> · 빌드 #N`, 본문 규칙(실패 줄 앞뒤 3줄·150줄·60KB·비밀값 줄 제거)은 「실패 댓글」과 같고 빌드 URL은 넣지 않는다. PR을 못 찾으면(직접 push) 생략.
- 자격은 `Nodes.GITHUB_API_CREDENTIALS`(`statuses`·`issues` 쓰기). 상태·댓글이 실패하면 경고만 — 배포 결과는 그대로다. 배포 경로에 더해지는 것은 GitHub POST 두 번(시작·끝, 각 `--max-time 10`)과 `tee` 뿐이다.
- 한 push에 PR 여러 개가 묶이면 끝 커밋만 배포되고 그 앞 병합 커밋에는 상태가 없다. `wait-deploy.py`는 그때 main에서 그 커밋 뒤의 첫 `sandcorp/deploy` 커밋을 본다.

### 재검사 생략

main 병합 커밋 M을 PR 검사(`continuous-integration/jenkins/pr-merge`)가 이미 본 트리라고 **증명할 수 있을 때만** 배포 전 `check`를 건너뛴다(`kr.sandcorp.ci.PrCheckSkip`, 값은 gate가 built-in에서 GitHub REST로 모은다 — 자격 `Nodes.GITHUB_API_CREDENTIALS`).

1. `commits/M/pulls`에서 M을 `merge_commit_sha`로 낸, 배포 브랜치로 병합된 PR이 정확히 하나.
2. 그 PR head H의 `pr-merge` 상태(문맥마다 최신)가 `success`.
3. `compare/P...H`의 `behind_by == 0` — 병합 직전 main P가 H의 조상. PR 검사는 H를 그때의 main B(⊆ P)에 머지해 보는데, B가 H의 조상이면 머지 결과가 H 그대로라 검사한 트리 = tree(H).
4. `tree(M) == tree(H)`(squash·merge 커밋 모두).

하나라도 어긋나거나 API가 실패하면 재검사한다. 생략하면 콘솔(`== PR #N 검사 통과(tree … 같음)로 생략`)과 빌드 설명에 남는다. PR 검사 잡의 PR 항목은 병합 뒤 사라지므로 Jenkins 기록이 아니라 GitHub 상태로 본다. 배포 `check`와 `Jenkinsfile.pr`의 `check`가 같은 명령이어야 이 생략이 맞다 — 다르게 둘 저장소는 `skipCheckedTree: false`.

### 자동 롤백

주 서비스를 새 이미지로 바꾼 **뒤**(교체·healthy 대기·딸린 서비스·헬스 중 하나라도) 실패하면, **직전 성공 빌드가 올린 sha**(`DEPLOY_SHA`) 이미지로 주 서비스와 `extraServices`를 다시 바꾸고 healthy·헬스를 다시 본다.

| `deploy.sh` 종료 | 뜻 | 빌드 |
|---|---|---|
| 0 | 성공 | SUCCESS |
| 1 | 교체 **전** 실패(pull·DB·migrate) — 살아 있는 서비스는 옛 이미지 그대로 | FAILURE |
| 2 | 교체 뒤 실패, 직전 성공 이미지가 없다(첫 배포 등) | FAILURE |
| 3 | 교체 뒤 실패 → 직전 이미지로 되돌렸고 헬스 통과 | FAILURE + ↩️ 알림 |
| 4 | 되돌리기도 실패 — 운영이 내려가 있을 수 있다 | FAILURE + 🚨 알림 |

- **migrate는 되돌리지 않는다.** 옛 코드가 새 스키마와 호환돼야 한다(파괴적 migrate는 PR 검사가 막는다 — 아래 「파괴적 migrate 검사」).
- 자동 롤백 경로는 운영에서 일부러 깨뜨려 시험하지 않는다. `tests/deploy_test.sh`가 가짜 docker로 헬스 실패·unhealthy·롤백 실패·직전 없음·migrate 실패·pull 실패를 돌린다.
- 자동 롤백한 빌드는 FAILURE라 다음 빌드의 「직전 성공」은 여전히 되돌린 그 sha다.

### 수동 되돌리기

「Build with Parameters」에 `ROLLBACK_SHA=<sha 40자>`. 빌드 없이 그 sha 이미지를 같은 절차(migrate 포함 — 옛 코드의 migrate는 할 일이 없다)로 배포한다. 이미 배포한 sha여도 다시 배포하니, 같은 sha를 강제로 다시 배포할 때도 쓴다. compose 파일은 지금 main 것을 쓴다.

### 저장소 배포 스크립트(`deployScript`)

라이브러리 순서로 안 되는 저장소(daypage: backend 두 대 롤링·Caddy reload)는 `deployScript: 'deploy/deploy.sh'`. 라이브러리는 이미지 변수(`images[].envVar`)와 `COMPOSE_PROJECT_NAME`·`COMPOSE_FILE`·`DEPLOY_IMAGES`·`SANDCORP_IMAGE_SHA`(이번에 올릴 이미지 태그 = 커밋 sha)·`SANDCORP_ROLLBACK=0`을 넘겨 부른다. compose가 이미지 태그 하나(`${IMAGE_TAG}`)로 여러 이미지를 고정하는 저장소(sandcorp-flow)는 `SANDCORP_IMAGE_SHA`를 쓴다. 0이 아니면 직전 성공 sha 이미지로(`SANDCORP_IMAGE_SHA`도 그 sha) **같은 스크립트를 `SANDCORP_ROLLBACK=1`로 한 번 더** 부른다 — 스크립트는 그때 migrate를 건너뛰어야 한다. 단계를 구분하지 못하므로 교체 전 실패에도 되돌리기를 한 번 돈다(옛 이미지를 다시 올릴 뿐이다).

## PR 검사(`sandcorpPrCheck`)

PR이 열리거나 push되면 Jenkins가 빌드 노드(**home 먼저**, `WAIT_SECONDS` 안에 못 잡으면 macos — `Nodes.PR_CHECK_ORDER`)에서 **PR 머지 결과**(PR head를 main에 합친 것)를 받아 ① 파괴적 migrate 검사 ② 저장소 검사(`check`)를 돌린다.
결과는 github-branch-source가 **PR head 커밋 상태** `continuous-integration/jenkins/pr-merge`(대기 → success/failure, 링크는 빌드)로 올린다.
작업자는 `wait-ci.py <PR>`(dev-kit issue-track)로 그 상태를 기다리고 SUCCESS일 때만 병합한다 — wait-ci.py는 커밋 상태(StatusContext)와 check-runs를 함께 읽고, 빨강이면 아래 「실패 댓글」을 출력한다(Jenkins 자격이 있는 곳에서는 콘솔에서 실패 줄을 뽑는다).
조직이 GitHub 무료 요금제라 보호 규칙으로 병합을 막지는 못한다. 강제는 두 겹: 작업자 규칙(wait-ci.py 통과 뒤 병합) + 배포 잡의 배포 전 `check`.

저장소에 `Jenkinsfile.pr`:

```groovy
@Library('sandcorp') _

sandcorpPrCheck(
  name: 'Sandcorp',
  check: 'bash scripts/ci/check.sh',   // 배포 잡의 check와 같은 것(운영 빌드 포함)
  buildSsh: true,                      // check가 비공개 git 의존성을 받으면(GIT_SSH_KEY)
)
```

| 인자 | 기본 | 뜻 |
|---|---|---|
| `check` | (필수) | 검사 명령. 빌드 노드에서 PR 머지 결과를 체크아웃한 뒤 돈다. `SANDCORP_BUILT_ON`(macos/home)·`SANDCORP_PR_CHECK=1`, `buildSsh`면 `GIT_SSH_KEY` |
| `name` | 빈 값 | 표시용 |
| `buildSsh` | `false` | `github-farrar142` 키 파일을 `GIT_SSH_KEY`로 넘긴다(ssh-agent는 check가 띄운다) |
| `migrationGuard` | `true` | 파괴적 migrate 검사. Django가 아닌 저장소도 켜 둬도 된다(새 migration이 없으면 통과) |
| `target` | `main` | `CHANGE_TARGET`이 없을 때의 기준 브랜치 |
| `prefer` | `home` | 먼저 기다릴 노드. `macos`면 배포처럼 macos 먼저(아래 「home에서 도는 검사」) |
| `waitSeconds` | `Nodes.PR_CHECK_WAIT_SECONDS`(180) | 먼저 노드를 기다리는 시간. 옛 이름 `macWaitSeconds`도 받는다 |
| `timeoutMinutes` | `60` | 빌드 시간 제한 |

같은 PR에 새 push가 오면 돌던 빌드를 끊는다(`disableConcurrentBuilds(abortPrevious: true)`). 잡 파라미터 `BUILDER`(auto/home/macos)·`WAIT_SECONDS`(첫 빌드 뒤 등록).

### 실패 댓글

작업자(work)는 Jenkins 콘솔을 읽을 자격을 두지 않는다(2026-11-04). 그래서 「파괴적 migrate」·「검사」 단계가 실패하면 Jenkins가 **PR에 댓글 하나**를 단다(`CHANGE_ID`가 있을 때만).

```
PR 검사 실패 — 검사 · 0123456789ab · home · 빌드 #42

<실패 요지>
종료 코드 1 · <빌드 URL>
```

- 단계 명령은 `sh`와 같게(`sh -xe`) 돌되 출력을 `tee`로 작업 공간 `@tmp/pr-check-{migrate,check}.log`에도 남긴다. 성공하면 그것뿐이다(댓글 없음).
- 요지(`kr.sandcorp.ci.FailureComment`): 출력 끝 2000줄에서 `FAILED`·`Error`·`error:`·`Traceback`·`AssertionError`·`E   `·`budget`/`예산` 줄과 앞뒤 3줄, 끝 쪽부터 **최대 150줄·60KB**. 뽑을 게 없으면 끝 80줄.
- 댓글은 저장소를 읽는 누구나 본다. `ghp_`·`github_pat_`·`-----BEGIN`·`password=`·`token=`·`https://discord`가 든 줄과 개인 키 블록(BEGIN…END, PGP `… PRIVATE KEY BLOCK`도)은 지운다. 끝 2000줄 경계가 키 블록 가운데에 걸려 BEGIN 없이 END가 먼저 나오면 첫 줄부터 그 END까지 지우고, 지우기는 줄 자르기(2000자)보다 먼저 한다. PR 검사의 `withCredentials`는 SSH 키 **파일 경로**(`GIT_SSH_KEY`)만 넘기므로 출력에 키 값이 나올 길은 `check`가 키 파일을 찍는 것뿐이고, 그 블록은 지워진다.
- head는 PR head 커밋(머지 결과의 두 번째 부모, 빨리감기면 HEAD) — wait-ci.py가 PR `headRefOid` 앞 12자로 찾는다. 새 push로 다시 실패하면 새 댓글이 붙는다(옛 댓글은 그대로).
- 자격은 배포의 재검사 판정과 같은 `github-sandcorporation-token`(`Nodes.GITHUB_API_CREDENTIALS`, 멀티브랜치 소스의 그것)으로 `POST /repos/{owner}/{repo}/issues/{N}/comments`. 못 달면(권한·네트워크) 경고만 찍고 빌드 결과는 단계 실패 그대로다.

### home에서 도는 검사

home은 Jenkins **컨테이너**(built-in 노드)이고 호스트의 docker 소켓을 쓴다. 그래서 `check`가 워크스페이스 경로를 bind mount(`docker run -v "$PWD/…"`, compose의 `./…` volumes)하거나 컨테이너가 띄운 호스트 포트(`localhost:<포트>`)에 붙으면 home에서는 깨진다(호스트에 그 경로가 없다). `docker build`의 빌드 컨텍스트와 `docker exec`·`docker run -i`로 파이프하는 것은 괜찮다.
`check`가 home에서 일부를 건너뛰어야 하는 저장소(`SANDCORP_BUILT_ON=home` 분기)는 `prefer: 'macos'`로 둔다 — PR 검사가 조용히 줄지 않게. 2026-11-02 기준 sandcorp-flow만 그렇다(nginx 계약 시험·e2e).

### 잡

저장소마다 **PR만 발견하는 멀티브랜치 잡** 하나(본보기: `codemit/ci-pr`). 이름은 `<배포 잡>-pr`(예: `sandcorp-portfolio-pr`).

- 소스 GitHub(`github-sandcorporation-token`), 발견: **원본 저장소 PR — 머지 결과**(`OriginPullRequestDiscoveryTrait` strategyId 1)만. 브랜치 발견은 없다(main은 배포 잡 몫).
- refspec에 `+refs/heads/main:refs/remotes/@{remote}/main`을 더한다 — 파괴적 migrate 검사가 `origin/main..HEAD`로 PR이 더한 파일을 가른다(없으면 머지 커밋의 첫 부모로 대신한다).
- Script Path `Jenkinsfile.pr`. 닫힌 PR 항목은 지우고(돌던 빌드도 끊음), 주기 스캔은 웹훅이 빠졌을 때의 그물(1시간).
- GitHub 웹훅(`/github-webhook/`)에 **Pull requests** 이벤트가 있어야 PR 열기·push에 바로 돈다.
- 상태 보고는 소스의 기본 동작이라 따로 설정하지 않는다(토큰에 `repo:status` 권한). 실패 댓글은 같은 토큰에 이슈·PR 쓰기 권한이 있어야 한다.

## 파괴적 migrate 검사

배포는 **옛 서비스가 도는 동안** migrate하고, 헬스가 실패하면 옛 이미지로 자동 롤백한다(migrate는 되돌리지 않는다). 그래서 새 스키마는 옛 코드가 그대로 읽고 쓸 수 있어야 한다.
PR 검사의 「파괴적 migrate」 단계가 PR이 **새로 더한** `*/migrations/*.py`(기준 `origin/main..HEAD`, 추가된 파일만 — 이미 있던 파일 수정은 보지 않는다)를 읽어(파이썬 `ast`, Django를 띄우지 않는다) 아래가 있으면 실패시킨다.

| 연산 | 왜 |
|---|---|
| `RemoveField` · `DeleteModel` | 옛 코드가 아직 그 열·표를 읽는다 |
| `RenameField` · `RenameModel` | 옛 코드는 옛 이름을 읽는다 |
| `AlterField`로 null 가능 → 불가 | 옛 코드가 NULL을 넣는다. 같은 앱의 앞 migration(이름순)에서 이전 정의를 따라간다 — 못 찾으면 실패로 본다 |
| `RunSQL`의 `DROP`·`RENAME`(SQL 주석 밖) | 옛 코드가 지운·바뀐 것을 읽는다. 변수·f-string처럼 글자 그대로가 아닌 SQL은 실패로 본다. `reverse_sql`은 보지 않는다 |
| `AddField`가 null 불가인데 `db_default`가 없음 | 롤백된 옛 코드의 INSERT가 그 열을 몰라 넣지 않아 실패한다(`default`는 Django 쪽 값이라 DB에 없다). `ManyToManyField`, 이 PR의 새 migration이 `CreateModel`한 모델의 필드는 제외(옛 코드가 그 표를 모른다) |

`SeparateDatabaseAndState`는 `database_operations`만 본다.

null 불가 새 열은 둘 중 하나로 고친다.

- **`db_default`를 준다**(Django 5.0+): `models.IntegerField(default=0, db_default=0)`. DB가 기본값을 채우니 옛 코드의 INSERT도 통과한다. 한 번의 배포로 끝난다.
- **두 번에 나눈다**: 먼저 `null=True`로 더해 배포하고(코드는 값을 채운다), 그 배포가 끝난 뒤의 PR에서 빈 값을 채우고 null 불가로 바꾸는 migration에 통과 표시를 단다(아래). 이때 `AlterField` null 가능 → 불가도 같은 표시로 통과한다.

### 통과 표시와 expand/contract

열·표를 지우거나 바꾸는 일은 **두 번의 배포**로 나눈다.

1. **expand**: 코드에서 그 열·표를 안 쓰게 하는 PR(필요하면 새 열을 null 가능·`db_default`로 더한다). 배포한다. 이 상태로 자동 롤백돼도 옛 코드와 새 스키마가 맞는다.
2. **contract**: 그 배포가 **끝난 뒤의** PR에서 지우기·바꾸기 migration을 만들고, 파일에 한 줄을 단다.

```python
# sandcorp: destructive-ok — post.old 읽기는 #12 배포(2026-11-01)에서 걷어냈다
```

이 표시의 뜻은 **「이 migration이 지우는 것을 코드에서 이미 안 쓰게 한 배포가 끝났다」**이다(contract 단계라는 선언). 이유는 필수다(구분자 `—`·`-`·`:`; 이유가 없으면 통과하지 않는다). 표시가 있으면 그 파일 전체가 통과하고 로그에 이유가 찍힌다.
로컬에서 미리 보기: `bash <jenkins-shared>/resources/sandcorp/migrate-guard.sh origin/main`(대상 저장소 루트에서).

## 노드 라벨 바꾸는 곳

`src/kr/sandcorp/ci/Nodes.groovy` 하나뿐이다(배포와 PR 검사가 같이 쓴다)(`MAC_BUILD`·`HOME_BUILD`·`DEPLOY`·`CONTROLLER`, 배포·PR 검사의 노드 순서 `DEPLOY_BUILD_ORDER`·`PR_CHECK_ORDER`, 자격 ID, 대기 기본값).
main에 병합하면 이 라이브러리를 쓰는 모든 저장소의 다음 빌드부터 바뀐다. `HOME_BUILD`는 `built-in`(예전 `laurio-staging` 라벨은 쓰지 않는다).

## 시험

```
bash tests/run.sh
```

도커·git이 있으면 된다: ① `tests/deploy_test.sh`(가짜 docker로 `deploy.sh` 성공·자동 롤백 경로) · `tests/build_image_test.sh`(`build-image.sh`의 buildx 인자 — 캐시 주소·ssh) ② `tests/DeployGateTest.groovy`(배포할지 판단) · `tests/PrCheckSkipTest.groovy`(재검사 생략 판정) · `tests/NodesTest.groovy`(노드 순서) · `tests/FailureCommentTest.groovy`(실패 댓글 요지·비밀값 지우기) ③ Groovy 전부 `groovyc` 컴파일 ④ shellcheck ⑤ `tests/migrate_guard_test.py`(파괴적 migrate 판정, `python:3.9-slim` — 맥 노드 python3 호환) · `tests/migrate_guard_sh_test.sh`(새 migration만 고르기, git). GitHub Actions(`.github/workflows/test.yml`)가 PR마다 같은 것을 돌린다.
Jenkins DSL(`pipeline`·`node`·`sh`)을 흉내 내는 시험은 없다 — 그 부분은 `@Library('sandcorp@<브랜치>')`로 실제 잡에서 본다.
