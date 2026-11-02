import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic
import kr.sandcorp.ci.DeployGate
import kr.sandcorp.ci.Nodes
import kr.sandcorp.ci.PrCheckSkip

/**
 * main 배포 파이프라인 — 저장소 Jenkinsfile 은 이것 한 번만 부른다. 인자·동작은 README.md.
 *
 *   배포할 커밋 확인(built-in, 재검사 생략 판정) → 이미지 빌드(macos, 못 잡으면 home; 검사와 나란히) → 배포(oracle, 실패하면 자동 롤백) → 디스코드
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
            choice(name: 'BUILDER', choices: ['auto', 'macos', 'home'], description: 'auto=macos 먼저, 못 잡으면 home. 시험용으로 강제 선택')
            string(name: 'MAC_WAIT_SECONDS', defaultValue: "${cfg.macWaitSeconds}", description: 'auto에서 macos 실행기를 기다리는 시간(초). 넘으면 home')
            string(name: 'ROLLBACK_SHA', defaultValue: '', description: '비우지 않으면 빌드 없이 이 커밋 sha의 이미지(ghcr)로 되돌린다(이미 배포한 sha 여도 다시 배포)')
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

            stage('이미지 빌드') {
                when { expression { env.SANDCORP_SKIP != 'true' && !params.ROLLBACK_SHA?.trim() } }
                steps {
                    script { buildOnNodes(cfg) }
                }
            }

            stage('배포') {
                when { expression { env.SANDCORP_SKIP != 'true' } }
                steps {
                    script { deployOnOracle(cfg) }
                }
            }
        }

        post {
            // 결과 한 줄을 디스코드로. 건너뛴 빌드(NOT_BUILT)는 알리지 않는다. 알림 실패는 빌드 결과를 뒤집지 않는다.
            always {
                script {
                    if (env.SANDCORP_SKIP != 'true') { notifyDiscord(cfg) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 인자

Map normalize(Map a) {
    def need = { String k -> if (!a[k]) { error("sandcorpDeploy: 인자 ${k} 가 없다 — README.md") } }
    need('name')
    need('composeProject')
    List images = a.images ?: (a.image ? [[repo: a.image, envVar: a.imageVar]] : [])
    if (!images) { error('sandcorpDeploy: image(+imageVar) 또는 images 가 없다 — README.md') }
    images = images.collect { Map im ->
        if (!im.repo || !im.envVar) { error("sandcorpDeploy: images 항목마다 repo·envVar 가 있어야 한다: ${im}") }
        [repo: im.repo, envVar: im.envVar, dockerfile: im.dockerfile ?: 'Dockerfile', context: im.context ?: '.',
         platform: im.platform ?: a.platform ?: 'linux/arm64']
    }
    return [
        name          : a.name,
        images        : images,
        sourceUrl     : a.sourceUrl ?: '',
        buildSsh      : a.buildSsh ? true : false,
        builder       : a.builder ?: "${a.composeProject}-images".toString(),
        check         : a.check ?: '',
        // PR 검사가 main 에 들어온 바로 그 트리를 통과시켰으면 배포 전 재검사를 건너뛴다(README 「재검사 생략」). false 면 늘 재검사.
        skipCheckedTree: a.skipCheckedTree == null ? true : (a.skipCheckedTree ? true : false),
        branch        : a.branch ?: 'main',
        composeProject: a.composeProject,
        composeFiles  : (a.composeFiles ?: ['docker-compose.prod.yml']).join(':'),
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
        macWaitSeconds: (a.macWaitSeconds ?: Nodes.MAC_WAIT_SECONDS) as int,
        timeoutMinutes: (a.timeoutMinutes ?: 90) as int,
    ]
}

// ---------------------------------------------------------------- 1. 배포할 커밋 확인

void gate(Map cfg) {
    String rollbackSha = params.ROLLBACK_SHA?.trim() ?: ''
    node(Nodes.CONTROLLER) {
        def v = checkout scm
        env.SANDCORP_MAIN_SHA = v.GIT_COMMIT
        env.SANDCORP_BRANCH = v.GIT_BRANCH ?: ''
    }
    Map decision = DeployGate.decide([
        mainSha     : env.SANDCORP_MAIN_SHA,
        branch      : env.SANDCORP_BRANCH,
        deployBranch: cfg.branch,
        rollbackSha : rollbackSha,
        manual      : !currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause').isEmpty(),
        lastSuccess : shaOf(currentBuild.previousSuccessfulBuild),
        lastAttempt : lastAttempt(),
    ])
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
    if (!rollbackSha && cfg.check) {
        Map s = cfg.skipCheckedTree ? checkedTree(cfg) : PrCheckSkip.recheck('skipCheckedTree: false')
        echo "== ${s.reason}"
        if (s.skip) {
            env.SANDCORP_CHECK_SKIPPED = s.reason
            currentBuild.description += " · ${s.reason}"
        }
    }
}

// PR 검사가 main 의 이 트리를 이미 통과시켰는가(kr.sandcorp.ci.PrCheckSkip). 무엇이든 실패하면 재검사 쪽으로 간다.
Map checkedTree(Map cfg) {
    try {
        // node·withCredentials 는 몸통이 돌려준 값을 그대로 돌려준다.
        return node(Nodes.CONTROLLER) {
            String main = env.SANDCORP_MAIN_SHA
            String repo = PrCheckSkip.repoSlug(scm.userRemoteConfigs[0].url)
            if (!repo) { return PrCheckSkip.recheck("GitHub 저장소를 모른다: ${scm.userRemoteConfigs[0].url}") }
            // 이 노드 작업 공간은 gate 가 방금 main 을 체크아웃한 곳이다.
            List mine = sh(returnStdout: true, script: "git rev-parse '${main}^{tree}' '${main}^1'").trim().split('\\s+') as List
            return withCredentials([usernamePassword(credentialsId: Nodes.GITHUB_API_CREDENTIALS, usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
                Map pick = PrCheckSkip.pickPr(main, cfg.branch, githubApi(repo, "commits/${main}/pulls") as List)
                if (!pick.pr) { return PrCheckSkip.recheck(pick.reason) }
                String head = pick.pr.headSha
                return PrCheckSkip.decide(
                    pr: pick.pr,
                    mainTree: mine[0],
                    parentSha: mine[1],
                    statuses: (githubApi(repo, "commits/${head}/status") as Map).statuses,
                    compare: githubApi(repo, "compare/${mine[1]}...${head}") as Map,
                    headTree: (githubApi(repo, "git/commits/${head}") as Map).tree?.sha,
                )
            }
        }
    } catch (err) {
        // 사람이 빌드를 멈춘 것은 삼키지 않는다.
        if (err.getClass().getName().endsWith('FlowInterruptedException')) { throw err }
        return PrCheckSkip.recheck("판정 실패(${err.getMessage()})")
    }
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

// ---------------------------------------------------------------- 2. 이미지 빌드

void buildOnNodes(Map cfg) {
    // macos 먼저, MAC_WAIT_SECONDS 안에 못 잡으면 home — 규칙은 sandcorpBuildNode(PR 검사와 같다).
    int waitSec = (params.MAC_WAIT_SECONDS ?: "${cfg.macWaitSeconds}") as int
    sandcorpBuildNode(builder: params.BUILDER ?: 'auto', waitSeconds: waitSec) { String where -> buildHere(cfg, where) }
}

// 이 노드에서 배포할 sha 를 체크아웃하고, 검사와 이미지 굽기를 나란히 돌린다. where = macos | home.
// 검사가 실패하면 굽기를 끊고 빌드가 실패해 배포 단계로 가지 않는다(GitHub 무료 요금제라 병합을 막지 못해 여기서 막는다).
// 검사가 끝나기 전에 ghcr 에 <repo>:<sha> 가 올라갈 수는 있지만, 그 이미지를 운영에 올리는 길은 검사를 통과한 이 빌드의 배포 단계뿐이다
// (ROLLBACK_SHA 로 그 sha 를 일부러 고르는 경우는 사람의 선택이다).
void buildHere(Map cfg, String where) {
    checkout scm
    sh "git checkout -q --detach ${env.DEPLOY_SHA}"
    env.BUILT_ON = where
    String skipped = env.SANDCORP_CHECK_SKIPPED ?: ''
    currentBuild.description = "${DeployGate.short12(env.DEPLOY_SHA)} · 빌드 ${where}" + (skipped ? " · ${skipped}" : '')
    echo "== 빌드 노드: ${where} (${env.NODE_NAME})"
    String script = "${pwd(tmp: true)}/sandcorp-build-image.sh"
    writeFile file: script, text: libraryResource('sandcorp/build-image.sh')
    Closure images = {
        for (Map im in cfg.images) {
            withEnv([
                "IMAGE_REPO=${im.repo}", "IMAGE_SHA=${env.DEPLOY_SHA}", "BUILT_ON=${where}",
                "DOCKERFILE=${im.dockerfile}", "BUILD_CONTEXT=${im.context}", "PLATFORM=${im.platform}",
                "BUILDER_NAME=${cfg.builder}", "SOURCE_URL=${cfg.sourceUrl}", "USE_SSH=${cfg.buildSsh ? '1' : '0'}",
            ]) {
                sh "bash '${script}'"
            }
        }
    }
    withCredentials([
        string(credentialsId: Nodes.GHCR_PUSH_CREDENTIALS, variable: 'GHCR_TOKEN'),
        sshUserPrivateKey(credentialsId: Nodes.GIT_SSH_CREDENTIALS, keyFileVariable: 'GIT_SSH_KEY')
    ]) {
        if (!cfg.check) {
            images()
        } else if (skipped) {
            stage('검사') { echo "== 검사 생략: ${skipped}" }
            images()
        } else {
            parallel(
                failFast: true,
                '검사': {
                    stage('검사') {
                        withEnv(["SANDCORP_BUILT_ON=${where}"]) { sh cfg.check }
                    }
                },
                '이미지 굽기': images,
            )
        }
    }
}

// ---------------------------------------------------------------- 3. 배포(oracle)

void deployOnOracle(Map cfg) {
    node(Nodes.DEPLOY) {
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
    currentBuild.description = "${DeployGate.short12(env.DEPLOY_SHA)} · " + (rollback ? '되돌리기' : "빌드 ${env.BUILT_ON}") + ' · oracle pull'
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
    echo "== oracle은 굽지 않는다: ${imagesAt(cfg, env.DEPLOY_SHA)} 를 pull"
    int rc
    withEnv(common + imageVars + [
        "DEPLOY_IMAGES=${imagesAt(cfg, env.DEPLOY_SHA)}",
        "DEPLOY_PREVIOUS_IMAGES=${imagesAt(cfg, env.SANDCORP_PREVIOUS_SHA)}",
        'SANDCORP_ROLLBACK=0',
    ]) {
        rc = sh(script: "bash '${script}'", returnStatus: true)
    }
    if (rc != 0 && cfg.deployScript && env.SANDCORP_PREVIOUS_SHA) {
        // 저장소 배포 스크립트는 단계를 모르니, 실패하면 직전 이미지로 같은 스크립트를 한 번 더 돌린다(migrate 는 스크립트가 SANDCORP_ROLLBACK=1 을 보고 건너뛴다).
        echo "== 배포 실패(${rc}) — 직전 이미지 ${DeployGate.short12(env.SANDCORP_PREVIOUS_SHA)} 로 자동 롤백"
        List prevVars = cfg.images.collect { "${it.envVar}=${it.repo}:${env.SANDCORP_PREVIOUS_SHA}" }
        int rc2
        withEnv(common + prevVars + ["DEPLOY_IMAGES=${imagesAt(cfg, env.SANDCORP_PREVIOUS_SHA)}", 'DEPLOY_PREVIOUS_IMAGES=', 'SANDCORP_ROLLBACK=1']) {
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
            error('배포 실패 — 교체 뒤 헬스 실패, 되돌릴 직전 성공 이미지가 없다. 운영을 바로 봐야 한다')
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

// ---------------------------------------------------------------- 4. 디스코드

void notifyDiscord(Map cfg) {
    node(Nodes.CONTROLLER) {
        catchError(buildResult: null, stageResult: null, message: '디스코드 알림 실패 — 배포 결과와 무관하다') {
            withCredentials([string(credentialsId: Nodes.DISCORD_CREDENTIALS, variable: 'DISCORD_WEBHOOK')]) {
                writeFile file: 'discord-payload.json', text: JsonOutput.toJson([content: discordText(cfg)])
                sh 'curl -sS -X POST -H "Content-Type: application/json" --data @discord-payload.json "$DISCORD_WEBHOOK" > /dev/null || true'
            }
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
