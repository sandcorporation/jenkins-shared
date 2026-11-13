import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic
import kr.sandcorp.ci.DeployFrom
import kr.sandcorp.ci.DeployGate
import kr.sandcorp.ci.DeployStatus
import kr.sandcorp.ci.ExtraCheckouts
import kr.sandcorp.ci.FailureComment
import kr.sandcorp.ci.Nodes
import kr.sandcorp.ci.PrCheckSkip
import kr.sandcorp.ci.StepScript

/**
 * main 배포 파이프라인 — 저장소 Jenkinsfile 은 이것 한 번만 부른다. 인자·동작은 README.md.
 *
 *   GitHub Actions 가 빌드·게시한 GHCR 이미지 확인 → 배포(deployNode, 기본 oracle, 실패하면 자동 롤백) → 디스코드
 *   deployFrom: 'production' 이면 그 브랜치만 배포한다(README 「Deployment contract」)
 *   main 커밋에 GitHub 커밋 상태 sandcorp/deploy(시작 pending, 끝 success|failure), 실패하면 병합된 PR 에 실패 댓글 — README 「배포 결과(GitHub)」
 */
def call(Map args) {
    Map cfg = normalize(args)
    pipeline {
        agent none

        options {
            disableConcurrentBuilds()
            timeout(time: cfg.timeoutMinutes, unit: 'MINUTES')
            timestamps()
        }

        parameters {
            string(name: 'ROLLBACK_SHA', defaultValue: '', description: '비우지 않으면 GitHub Actions가 GHCR에 게시한 이 커밋의 이미지를 배포한다')
        }

        triggers {
            githubPush()
        }

        stages {
            stage('배포할 커밋 확인') {
                steps {
                    script { gate(cfg) }
                }
            }

            stage('배포') {
                when { expression { env.SANDCORP_SKIP != 'true' } }
                steps {
                    script { deployOnNode(cfg) }
                }
            }
        }

        post {
            // 커밋 상태(sandcorp/deploy)를 끝맺고 결과 한 줄을 디스코드로. 건너뛴 빌드(NOT_BUILT)는 디스코드에 알리지 않는다.
            // 상태·알림 실패는 빌드 결과를 뒤집지 않는다. 중단·시간 초과에도 always 는 돈다 — pending 으로 남지 않는다.
            always {
                script { finish(cfg) }
            }
        }
    }
}

// ---------------------------------------------------------------- 인자

String deployNodeOf(Object raw) {
    try {
        return Nodes.deployNode(raw)
    } catch (IllegalArgumentException e) {
        error("sandcorpDeploy: ${e.message} — README.md")
    }
}

List extraCheckoutsOf(Object raw) {
    try {
        return ExtraCheckouts.normalize(raw)
    } catch (IllegalArgumentException e) {
        error("sandcorpDeploy: ${e.message} — README.md")
    }
}

Map normalize(Map a) {
    List retired = ['check', 'buildSsh', 'builder', 'buildScript', 'buildCredentials', 'platform', 'sourceUrl', 'skipCheckedTree', 'macWaitSeconds']
    List supplied = retired.findAll { a.containsKey(it) }
    if (supplied) { error("sandcorpDeploy: ${supplied.join(', ')} 는 퇴역했다. GitHub Actions에서 검사·이미지 빌드를 하고 Jenkins에는 배포만 맡긴다 — README.md") }
    def need = { String k -> if (!a[k]) { error("sandcorpDeploy: 인자 ${k} 가 없다 — README.md") } }
    need('name')
    need('composeProject')
    List images = a.images ?: (a.image ? [[repo: a.image, envVar: a.imageVar]] : [])
    if (!images) { error('sandcorpDeploy: image(+imageVar) 또는 images 가 필요하다 — Jenkins 는 Actions가 게시한 GHCR 이미지만 배포한다 — README.md') }
    String branch
    try {
        branch = DeployFrom.branch(a.deployFrom ?: '', a.branch ?: '')
    } catch (IllegalArgumentException e) {
        error("sandcorpDeploy: ${e.message} — README.md")
    }
    images = images.collect { Map im ->
        if (!im.repo || !im.envVar) { error("sandcorpDeploy: images 항목마다 repo·envVar 가 있어야 한다: ${im}") }
        [repo: DeployFrom.ghcrRepo(im.repo), envVar: im.envVar]
    }
    return [
        name          : a.name,
        images        : images,
        // false 면 교체 뒤 실패에도 직전 이미지로 되돌리지 않는다(README 「자동 롤백」).
        autoRollback  : a.autoRollback == null ? true : (a.autoRollback ? true : false),
        branch        : branch,
        // 배포할 브랜치(production 등). 비면 branch 또는 main.
        deployFrom    : a.deployFrom ?: '',
        imageWaitMinutes: (a.imageWaitMinutes ?: DeployFrom.IMAGE_WAIT_MINUTES) as int,
        imageWaitHint : a.imageWaitHint ?: '',
        composeProject: a.composeProject,
        composeFiles  : (a.composeFiles ?: ['docker-compose.prod.yml']).join(':'),
        // 배포가 도는 Jenkins 노드 라벨. 비면 oracle — 지금까지와 같다(README 「배포 노드」).
        deployNode    : deployNodeOf(a.deployNode),
        deployDir     : a.deployDir ?: '',
        deployScript  : a.deployScript ?: '',
        service       : a.service ?: 'web',
        preServices   : (a.preServices ?: []).join(' '),
        dbCheck       : a.dbCheck ?: '',
        migrate       : (a.migrate instanceof List ? a.migrate : (a.migrate ? [a.migrate] : [])).join('\n'),
        extraServices : (a.extraServices ?: []).join(' '),
        keepServices  : (a.keepServices ?: []).join(' '),
        healthCmd     : a.healthCmd ?: '',
        healthUrl     : a.healthUrl ?: '',
        keepImages    : (a.keepImages ?: 3) as int,
        timeoutMinutes: (a.timeoutMinutes ?: 90) as int,
        // 배포(oracle) 단계에서 checkout scm 뒤에 고정 sha 로 받을 다른 저장소(README 「다른 저장소 받기」). 없으면 [] — 지금과 같다.
        extraCheckouts: extraCheckoutsOf(a.extraCheckouts),
    ]
}

// ---------------------------------------------------------------- 1. 배포할 커밋 확인

void gate(Map cfg) {
    String rollbackSha = params.ROLLBACK_SHA?.trim() ?: ''
    Map decision
    node(Nodes.CONTROLLER) {
        def v = checkout scm
        env.SANDCORP_MAIN_SHA = v.GIT_COMMIT
        env.SANDCORP_BRANCH = v.GIT_BRANCH ?: ''
        env.SANDCORP_DEPLOY_BRANCH = cfg.branch
        env.SANDCORP_REPO = PrCheckSkip.repoSlug(scm.userRemoteConfigs[0].url) ?: ''
        decision = DeployGate.decide([
            mainSha     : env.SANDCORP_MAIN_SHA,
            branch      : env.SANDCORP_BRANCH,
            deployBranch: cfg.branch,
            rollbackSha : rollbackSha,
            manual      : !currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause').isEmpty(),
            lastSuccess : shaOf(currentBuild.previousSuccessfulBuild),
            lastAttempt : lastAttempt(),
        ])
        env.SANDCORP_GATE_KIND = decision.kind
        // main 커밋을 배포할 때만 상태를 단다(ROLLBACK_SHA 되돌리기는 옛 sha 의 기록을 덮지 않게 달지 않는다).
        if (decision.deploy && !rollbackSha && env.SANDCORP_REPO) {
            env.SANDCORP_REPORT = 'true'
            postStatus(env.SANDCORP_MAIN_SHA, DeployStatus.pending(env.BUILD_NUMBER))
        }
    }
    echo "== ${decision.reason}"
    if (!decision.deploy) {
        env.SANDCORP_SKIP = 'true'
        currentBuild.result = 'NOT_BUILT'
        currentBuild.description = "건너뜀 · ${decision.reason}"
        return
    }
    env.DEPLOY_SHA = rollbackSha ?: env.SANDCORP_MAIN_SHA
    // 자동 롤백 대상: 직전 성공 빌드가 올린 sha. 새 sha 와 같으면 되돌릴 곳이 없다.
    String prev = currentBuild.previousSuccessfulBuild?.buildVariables?.DEPLOY_SHA ?: ''
    env.SANDCORP_PREVIOUS_SHA = (prev && prev != env.DEPLOY_SHA) ? prev : ''
    currentBuild.description = "${DeployGate.short12(env.DEPLOY_SHA)} · " + (rollbackSha ? '되돌리기' : '빌드 대기')
}

// GitHub REST GET. 토큰은 환경변수로만 넘기고 찍지 않는다. 실패(4xx·5xx·시간 초과)는 예외 → 재검사.
def githubApi(String repo, String path) {
    String out
    withEnv(["GH_URL=https://api.github.com/repos/${repo}/${path}"]) {
        out = sh(returnStdout: true, script: '''#!/bin/sh
set -e
curl -fsS --max-time 10 -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github+json" "$GH_URL"
''')
    }
    // JsonSlurperClassic 은 HashMap·ArrayList 를 돌려줘 CPS 직렬화에 걸리지 않는다(JsonSlurper 의 LazyMap 은 걸린다).
    return new JsonSlurperClassic().parseText(out)
}

// 이전 빌드에서 배포한 main sha. 라이브러리 전환 전 빌드는 SANDCORP_MAIN_SHA 대신 DEPLOY_SHA(=main)만 있다.
Map shaOf(def run) {
    if (run == null) { return null }
    def vars = run.buildVariables
    String main = vars.SANDCORP_MAIN_SHA ?: (vars.ROLLBACK_SHA ? '' : vars.DEPLOY_SHA)
    return main ? [main: main, result: run.result] : null
}

// 배포 단계까지 간(건너뛰지 않은) 가장 최근 빌드.
Map lastAttempt() {
    def run = currentBuild.previousBuild
    int seen = 0
    while (run != null && seen < 30) {
        if (run.result in ['SUCCESS', 'FAILURE'] && run.buildVariables.SANDCORP_SKIP != 'true') {
            Map s = shaOf(run)
            if (s) { return s }
        }
        run = run.previousBuild
        seen++
    }
    return null
}

// ---------------------------------------------------------------- 3. 배포(deployNode, 기본 oracle)

void deployOnNode(Map cfg) {
    node(cfg.deployNode) {
        if (cfg.deployDir) {
            ws(cfg.deployDir) { deployHere(cfg) }
        } else {
            deployHere(cfg)
        }
    }
}

String imagesAt(Map cfg, String sha) {
    return sha ? cfg.images.collect { "${it.envVar}=${it.repo}:${sha}" }.join(' ') : ''
}

void deployHere(Map cfg) {
    boolean rollback = params.ROLLBACK_SHA?.trim() ? true : false
    // compose 파일·저장소 배포 스크립트는 배포할 sha 의 것을 쓴다(되돌릴 때는 지금 main 것 + 옛 이미지).
    checkout scm
    if (!rollback) {
        sh "git checkout -q --detach ${env.DEPLOY_SHA}"
    }
    waitForImages(cfg)
    if (cfg.extraCheckouts) { fetchExtraCheckouts(cfg) }
    currentBuild.description = "${DeployGate.short12(env.DEPLOY_SHA)} · " + (rollback ? '되돌리기' : "Actions") + " · ${cfg.deployNode} pull"
    String script = cfg.deployScript ?: "${pwd(tmp: true)}/sandcorp-deploy.sh"
    if (!cfg.deployScript) {
        writeFile file: script, text: libraryResource('sandcorp/deploy.sh')
    }
    List common = [
        "COMPOSE_PROJECT_NAME=${cfg.composeProject}", "COMPOSE_FILE=${cfg.composeFiles}",
        "DEPLOY_SERVICE=${cfg.service}", "DEPLOY_PRE_SERVICES=${cfg.preServices}", "DEPLOY_DB_CHECK=${cfg.dbCheck}",
        "DEPLOY_MIGRATE=${cfg.migrate}", "DEPLOY_EXTRA_SERVICES=${cfg.extraServices}",
        "DEPLOY_KEEP_SERVICES=${cfg.keepServices}", "DEPLOY_HEALTH_CMD=${cfg.healthCmd}",
        "DEPLOY_HEALTH_URL=${cfg.healthUrl}", "DEPLOY_KEEP_IMAGES=${cfg.keepImages}",
    ]
    // 이미지 변수(PORTFOLIO_IMAGE 등)도 넘겨 저장소 배포 스크립트가 그대로 쓸 수 있게 한다.
    List imageVars = cfg.images.collect { "${it.envVar}=${it.repo}:${env.DEPLOY_SHA}" }
    echo(cfg.images ? "== ${cfg.deployNode}은 굽지 않는다: ${imagesAt(cfg, env.DEPLOY_SHA)} 를 pull" : "== ${cfg.deployNode}은 굽지 않는다: ${DeployGate.short12(env.DEPLOY_SHA)} 이미지를 배포 스크립트가 pull")
    // autoRollback: false 면 되돌릴 이미지를 주지 않는다 — 라이브러리 deploy.sh 는 종료 2(교체 뒤 실패, 되돌림 없음)로 끝난다.
    String previousImages = cfg.autoRollback ? imagesAt(cfg, env.SANDCORP_PREVIOUS_SHA) : ''
    int rc
    withEnv(common + imageVars + [
        "DEPLOY_IMAGES=${imagesAt(cfg, env.DEPLOY_SHA)}",
        "DEPLOY_PREVIOUS_IMAGES=${previousImages}",
        "SANDCORP_IMAGE_SHA=${env.DEPLOY_SHA}",
        'SANDCORP_ROLLBACK=0',
    ]) {
        rc = runLogged('배포', cfg.deployNode, "bash '${script}'", true)
    }
    if (rc != 0 && cfg.autoRollback && cfg.deployScript && env.SANDCORP_PREVIOUS_SHA) {
        // 저장소 배포 스크립트는 단계를 모르니, 실패하면 직전 이미지로 같은 스크립트를 한 번 더 돌린다(migrate 는 스크립트가 SANDCORP_ROLLBACK=1 을 보고 건너뛴다).
        echo "== 배포 실패(${rc}) — 직전 이미지 ${DeployGate.short12(env.SANDCORP_PREVIOUS_SHA)} 로 자동 롤백"
        List prevVars = cfg.images.collect { "${it.envVar}=${it.repo}:${env.SANDCORP_PREVIOUS_SHA}" }
        int rc2
        withEnv(common + prevVars + ["DEPLOY_IMAGES=${imagesAt(cfg, env.SANDCORP_PREVIOUS_SHA)}", 'DEPLOY_PREVIOUS_IMAGES=',
                 "SANDCORP_IMAGE_SHA=${env.SANDCORP_PREVIOUS_SHA}", 'SANDCORP_ROLLBACK=1']) {
            rc2 = sh(script: "bash '${script}'", returnStatus: true)
        }
        rc = rc2 == 0 ? 3 : 4
    }
    switch (rc) {
        case 0:
            return
        case 1:
            error('배포 실패 — 교체 전에 멈췄다(pull·DB·migrate). 살아 있는 서비스는 옛 이미지 그대로다')
            break
        case 2:
            error(cfg.autoRollback ? '배포 실패 — 교체 뒤 헬스 실패, 되돌릴 직전 성공 이미지가 없다. 운영을 바로 봐야 한다'
                                   : '배포 실패 — 교체 뒤 실패, 자동 롤백을 끈 저장소다(autoRollback: false). 운영을 바로 봐야 한다')
            break
        case 3:
            env.SANDCORP_ROLLED_BACK_TO = env.SANDCORP_PREVIOUS_SHA
            currentBuild.description = "${DeployGate.short12(env.DEPLOY_SHA)} 실패 → ${DeployGate.short12(env.SANDCORP_PREVIOUS_SHA)} 자동 롤백"
            error("배포 실패 — 헬스 실패로 ${DeployGate.short12(env.SANDCORP_PREVIOUS_SHA)} 로 자동 롤백했다(헬스 통과)")
            break
        default:
            env.SANDCORP_ROLLBACK_FAILED = 'true'
            error("배포 실패 — 자동 롤백도 실패했다(종료 ${rc}). 운영을 바로 봐야 한다")
    }
}

// deployFrom: Actions 가 승격 전에 이미 올린 이미지가 이 노드(배포 노드의 pull 자격)에서 보이는지 짧게 확인한다.
// 되돌리기(ROLLBACK_SHA)도 옛 이미지가 남아 있는지 교체 전에 본다. 없으면 교체 전에 끝나 살아 있는 서비스는 그대로다.
void waitForImages(Map cfg) {
    env.BUILT_ON = DeployFrom.ACTIONS
    try {
        waitForGhcrImage(images: cfg.images.collect { "${it.repo}:${env.DEPLOY_SHA}".toString() },
                         timeoutMinutes: cfg.imageWaitMinutes, intervalSeconds: 10, hint: cfg.imageWaitHint)
    } catch (err) {
        if (err.getClass().getName().endsWith('FlowInterruptedException')) { throw err }
        env.SANDCORP_FAILED_STAGE = '이미지 기다리기'
        throw err
    }
}

// extraCheckouts: 배포할 sha 의 refFile 이 고정한 커밋을 dir 에 얕게 받는다(교체 전 — 실패하면 살아 있는 서비스는 그대로).
// Actions 이미지 빌드에는 포함하지 않는다. 되돌리기(ROLLBACK_SHA)는 지금 main 의 refFile 을 쓴다.
void fetchExtraCheckouts(Map cfg) {
    String tmp = pwd(tmp: true)
    String script = "${tmp}/sandcorp-extra-checkout.sh"
    String knownHosts = "${tmp}/sandcorp-github-known-hosts"
    writeFile file: script, text: libraryResource('sandcorp/extra-checkout.sh')
    writeFile file: knownHosts, text: libraryResource('sandcorp/github_known_hosts')
    for (Map e in cfg.extraCheckouts) {
        String sha
        try {
            sha = ExtraCheckouts.parseRef(e.refFile, readFile(file: e.refFile, encoding: 'UTF-8'))
        } catch (IllegalArgumentException ex) {
            env.SANDCORP_FAILED_STAGE = '다른 저장소 받기'
            error("배포 실패 — ${ex.message}")
        }
        // dir 은 normalize 가 워크스페이스 바로 아래 한 칸 이름으로 검증했다. 지우기는 Jenkins deleteDir(셸 rm 아님).
        dir(e.dir) { deleteDir() }
        withCredentials([sshUserPrivateKey(credentialsId: e.credentialsId, keyFileVariable: 'SANDCORP_EXTRA_KEY')]) {
            int rc = sh(returnStatus: true, script: ExtraCheckouts.command(script, knownHosts, e, sha))
            if (rc != 0) {
                env.SANDCORP_FAILED_STAGE = '다른 저장소 받기'
                error("배포 실패 — ${e.url} @ ${DeployGate.short12(sha)} 받기 실패(종료 ${rc}). 교체 전이라 살아 있는 서비스는 그대로다")
            }
        }
    }
}

// ---------------------------------------------------------------- 4. 디스코드

// post always. 노드 하나(built-in)에서 커밋 상태를 끝맺고 디스코드에 알린다.
void finish(Map cfg) {
    if (env.SANDCORP_SKIP == 'true') {
        // 작업 브랜치 push 등으로 건너뛴 빌드는 남길 상태가 있을 때만 노드를 잡는다.
        if (env.SANDCORP_REPO && env.SANDCORP_MAIN_SHA && env.SANDCORP_GATE_KIND in ['deployed', 'failed']) {
            node(Nodes.CONTROLLER) { skipStatus() }
        }
        return
    }
    node(Nodes.CONTROLLER) {
        if (env.SANDCORP_REPORT == 'true') {
            postStatus(env.SANDCORP_MAIN_SHA, DeployStatus.finish(
                build         : env.BUILD_NUMBER,
                result        : currentBuild.currentResult,
                builtOn       : env.BUILT_ON ?: '',
                checkSkipped  : env.SANDCORP_CHECK_SKIPPED ?: '',
                failedStage   : env.SANDCORP_FAILED_STAGE ?: '',
                rolledBackTo  : env.SANDCORP_ROLLED_BACK_TO ?: '',
                rollbackFailed: env.SANDCORP_ROLLBACK_FAILED == 'true',
                took          : currentBuild.durationString.replace(' and counting', ''),
            ))
        }
        notifyDiscord(cfg)
    }
}

void notifyDiscord(Map cfg) {
    catchError(buildResult: null, stageResult: null, message: '디스코드 알림 실패 — 배포 결과와 무관하다') {
        withCredentials([string(credentialsId: Nodes.DISCORD_CREDENTIALS, variable: 'DISCORD_WEBHOOK')]) {
            writeFile file: 'discord-payload.json', text: JsonOutput.toJson([content: discordText(cfg)])
            sh 'curl -sS -X POST -H "Content-Type: application/json" --data @discord-payload.json "$DISCORD_WEBHOOK" > /dev/null || true'
        }
    }
}

String discordText(Map cfg) {
    String result = currentBuild.currentResult
    String sha = DeployGate.short12(env.DEPLOY_SHA ?: '')
    String took = currentBuild.durationString.replace(' and counting', '')
    String what
    if (env.SANDCORP_ROLLED_BACK_TO) {
        what = "↩️ [${cfg.name}] 배포 실패 → 자동 롤백 — ${sha} 헬스 실패, ${DeployGate.short12(env.SANDCORP_ROLLED_BACK_TO)} 로 되돌림(헬스 통과)"
    } else if (env.SANDCORP_ROLLBACK_FAILED) {
        what = "🚨 [${cfg.name}] 배포 실패 · 자동 롤백도 실패 — ${sha}"
    } else if (params.ROLLBACK_SHA?.trim()) {
        String mark = result == 'SUCCESS' ? '✅' : '❌'
        what = "${mark} [${cfg.name}] 되돌리기 ${result} — ${sha}"
    } else {
        String mark = result == 'SUCCESS' ? '✅' : (result == 'ABORTED' ? '⚪' : '❌')
        what = "${mark} [${cfg.name}] 배포 ${result} — ${sha}"
    }
    return "${what} · #${env.BUILD_NUMBER} · 빌드 ${env.BUILT_ON ?: '-'} · ${took} · ${env.BUILD_URL}".toString()
}

// ---------------------------------------------------------------- 5. GitHub 커밋 상태 · 실패 댓글(kr.sandcorp.ci.DeployStatus)
// 자격은 Nodes.GITHUB_API_CREDENTIALS(재검사 판정·PR 검사 실패 댓글과 같다). 토큰은 환경변수로만 넘기고 찍지 않는다.
// 무엇이 실패해도 경고만 — 배포 결과는 바꾸지 않는다. 사람이 빌드를 멈춘 것(FlowInterrupted)만 삼키지 않는다.

// 건너뛴 빌드: 그 main sha 에 끝난 상태가 아직 없을 때만 남긴다(작업 브랜치 push 마다 깨는 빌드가 기록을 덮지 않게).
void skipStatus() {
    String sha = env.SANDCORP_MAIN_SHA
    String repo = env.SANDCORP_REPO
    guarded('커밋 상태') {
        withCredentials([usernamePassword(credentialsId: Nodes.GITHUB_API_CREDENTIALS, usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
            String existing = DeployStatus.stateIn((githubApi(repo, "commits/${sha}/status") as Map).statuses as List)
            Map st = DeployStatus.onSkip(build: env.BUILD_NUMBER, kind: env.SANDCORP_GATE_KIND, existing: existing)
            if (st) {
                githubPost(repo, "statuses/${sha}", st, 'status')
                echo "== 커밋 상태 ${DeployStatus.CONTEXT}: ${st.state} · ${st.description}"
            }
        }
    }
}

void postStatus(String sha, Map st) {
    guarded('커밋 상태') {
        withCredentials([usernamePassword(credentialsId: Nodes.GITHUB_API_CREDENTIALS, usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
            githubPost(env.SANDCORP_REPO, "statuses/${sha}", st, 'status')
            echo "== 커밋 상태 ${DeployStatus.CONTEXT}: ${st.state} · ${st.description}"
        }
    }
}

// 단계 명령을 `sh` 와 같게(sh -xe) 돌리되 출력을 파일에도 남긴다(tee).
// 실패하면 병합된 PR 에 댓글을 달고, returnStatus 면 종료 코드를 돌려주고 아니면 같은 실패로 끝낸다.
int runLogged(String stageName, String where, String script, boolean returnStatus) {
    String tmp = pwd(tmp: true)
    String key = ['검사': 'check', '이미지 굽기': 'image', '배포': 'deploy'][stageName] ?: 'step'
    // 단계 명령 파일은 실제 스크립트(sandcorp-deploy.sh 등)와 겹치지 않는 이름 — 겹치면 자기 재귀(#19)
    String cmd = StepScript.path(tmp, 'sandcorp', key)
    String log = "${tmp}/sandcorp-${key}.log"
    StepScript.guard(cmd, script)
    writeFile file: cmd, text: script
    int rc = sh(returnStatus: true, script: """#!/bin/bash
sh -xe '${cmd}' 2>&1 | tee '${log}'
exit "\${PIPESTATUS[0]}"
""")
    if (rc != 0) {
        if (!env.SANDCORP_FAILED_STAGE) { env.SANDCORP_FAILED_STAGE = stageName }
        commentFailure(stageName, where, rc, log)
        if (!returnStatus) { error("${stageName}: script returned exit code ${rc}") }
    }
    return rc
}

// 배포한 sha 를 만든 병합 PR 에 실패 댓글 하나(kr.sandcorp.ci.FailureComment, 제목 앞머리 DeployStatus.COMMENT_PREFIX).
// PR 을 못 찾으면(직접 push 등) 생략. 빌드 URL 은 넣지 않는다(내부 주소).
void commentFailure(String stageName, String where, int rc, String log) {
    if (env.SANDCORP_REPORT != 'true') { return }
    guarded('PR 실패 댓글') {
        String repo = env.SANDCORP_REPO
        String sha = env.SANDCORP_MAIN_SHA
        String output = sh(returnStdout: true, script: "tail -n ${FailureComment.READ_LINES} '${log}' 2>/dev/null || true")
        withCredentials([usernamePassword(credentialsId: Nodes.GITHUB_API_CREDENTIALS, usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
            String pr = env.SANDCORP_PR ?: ''
            if (!pr) {
                Map pick = PrCheckSkip.pickPr(sha, env.SANDCORP_DEPLOY_BRANCH, githubApi(repo, "commits/${sha}/pulls") as List)
                if (!pick.pr) {
                    echo "== PR 실패 댓글 생략: ${pick.reason}"
                    return
                }
                pr = "${pick.pr.number}"
                env.SANDCORP_PR = pr
            }
            String body = FailureComment.body(prefix: DeployStatus.COMMENT_PREFIX, stage: stageName, head: sha, where: where,
                build: env.BUILD_NUMBER, code: rc, output: output)
            githubPost(repo, "issues/${pr}/comments", [body: body], 'comment')
            echo "== PR #${pr} 에 배포 실패 댓글을 달았다"
        }
    }
}

// GitHub REST POST. 몸통은 파일로(커질 수 있다). 실패(4xx·5xx·시간 초과)는 예외 — 부르는 쪽 guarded 가 경고로 바꾼다.
void githubPost(String repo, String path, Map payload, String key) {
    String json = "${pwd(tmp: true)}/sandcorp-github-${key}.json"
    writeFile file: json, text: JsonOutput.toJson(payload)
    withEnv(["GH_URL=https://api.github.com/repos/${repo}/${path}", "GH_BODY=${json}"]) {
        sh '''#!/bin/sh
set -e
curl -fsS --max-time 10 -o /dev/null -X POST -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github+json" --data @"$GH_BODY" "$GH_URL"
'''
    }
}

void guarded(String what, Closure body) {
    try {
        body()
    } catch (err) {
        if (err.getClass().getName().endsWith('FlowInterruptedException')) { throw err }
        echo "경고: ${what} 실패(${err.getMessage()}) — 배포 결과는 그대로. 자격 ${Nodes.GITHUB_API_CREDENTIALS} 의 statuses·issues 쓰기 권한을 본다"
    }
}
