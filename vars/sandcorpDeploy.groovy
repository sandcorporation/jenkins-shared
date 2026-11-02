import groovy.json.JsonOutput
import kr.sandcorp.ci.DeployGate
import kr.sandcorp.ci.Nodes

/**
 * main 배포 파이프라인 — 저장소 Jenkinsfile 은 이것 한 번만 부른다. 인자·동작은 README.md.
 *
 *   배포할 커밋 확인(built-in) → 이미지 빌드(macos, 못 잡으면 home; 검사 먼저) → 배포(oracle, 실패하면 자동 롤백) → 디스코드
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
    String b = params.BUILDER ?: 'auto'
    if (b == 'macos') {
        node(Nodes.MAC_BUILD) { buildHere(cfg, 'macos') }
        return
    }
    if (b == 'home') {
        node(Nodes.HOME_BUILD) { buildHere(cfg, 'home') }
        return
    }
    // auto: macos 를 잡으면 거기서 끝까지 굽는다. 대기가 길어지면 감시가 실패해 failFast 로 mac 대기를 걷어 내고
    // (대기열 항목이 취소된다) home 으로 간다. 실행기를 잡은 뒤의 실패(검사·빌드)는 그대로 실패다.
    env.MAC_ACQUIRED = 'false'
    int waitSec = (params.MAC_WAIT_SECONDS ?: "${cfg.macWaitSeconds}") as int
    try {
        parallel(
            failFast: true,
            'macos': {
                node(Nodes.MAC_BUILD) {
                    env.MAC_ACQUIRED = 'true'
                    buildHere(cfg, 'macos')
                }
            },
            '대기 감시': {
                int waited = 0
                while (env.MAC_ACQUIRED != 'true' && waited < waitSec) {
                    sleep time: 5, unit: 'SECONDS'
                    waited += 5
                }
                if (env.MAC_ACQUIRED != 'true') {
                    error("macos 실행기를 ${waitSec}초 안에 못 잡았다")
                }
            }
        )
    } catch (err) {
        if (env.MAC_ACQUIRED == 'true') { throw err }
        echo "macos를 못 잡아 home에서 굽는다: ${err.getMessage()}"
        node(Nodes.HOME_BUILD) { buildHere(cfg, 'home') }
    }
}

// 이 노드에서 배포할 sha 를 체크아웃하고, 검사를 돌린 뒤 이미지를 굽는다. where = macos | home.
void buildHere(Map cfg, String where) {
    checkout scm
    sh "git checkout -q --detach ${env.DEPLOY_SHA}"
    env.BUILT_ON = where
    currentBuild.description = "${DeployGate.short12(env.DEPLOY_SHA)} · 빌드 ${where}"
    echo "== 빌드 노드: ${where} (${env.NODE_NAME})"
    String script = "${pwd(tmp: true)}/sandcorp-build-image.sh"
    writeFile file: script, text: libraryResource('sandcorp/build-image.sh')
    withCredentials([
        string(credentialsId: Nodes.GHCR_PUSH_CREDENTIALS, variable: 'GHCR_TOKEN'),
        sshUserPrivateKey(credentialsId: Nodes.GIT_SSH_CREDENTIALS, keyFileVariable: 'GIT_SSH_KEY')
    ]) {
        if (cfg.check) {
            // 검사가 실패하면 이미지를 굽지 않고, 배포도 하지 않는다(GitHub 무료 요금제라 병합을 막지 못해 여기서 막는다).
            stage('검사') {
                withEnv(["SANDCORP_BUILT_ON=${where}"]) { sh cfg.check }
            }
        }
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
