import kr.sandcorp.ci.Nodes

/**
 * PR 검사 파이프라인 — 저장소 Jenkinsfile.pr 은 이것 한 번만 부른다. 잡은 PR 만 발견하는 멀티브랜치(README 「PR 검사」).
 *
 *   빌드 노드(macos, 못 잡으면 home)에서 PR 머지 결과 체크아웃 → 파괴적 migrate 검사 → check
 *
 * 결과는 github-branch-source 가 PR head 커밋 상태(continuous-integration/jenkins/pr-merge)로 올린다 —
 * 작업자는 wait-ci.py 로 그것을 기다린 뒤 병합한다. 새 push 가 오면 돌던 빌드는 끊는다.
 */
def call(Map args) {
    Map cfg = normalize(args)

    pipeline {
        agent none

        options {
            disableConcurrentBuilds(abortPrevious: true)
            timeout(time: cfg.timeoutMinutes, unit: 'MINUTES')
            timestamps()
            buildDiscarder(logRotator(numToKeepStr: '20'))
        }

        parameters {
            choice(name: 'BUILDER', choices: ['auto', 'macos', 'home'], description: 'auto=macos 먼저, 못 잡으면 home. 시험용으로 강제 선택')
            string(name: 'MAC_WAIT_SECONDS', defaultValue: "${cfg.macWaitSeconds}", description: 'auto에서 macos 실행기를 기다리는 시간(초). 넘으면 home')
        }

        stages {
            stage('PR 검사') {
                steps {
                    script {
                        int waitSec = (params.MAC_WAIT_SECONDS ?: "${cfg.macWaitSeconds}") as int
                        sandcorpBuildNode(builder: params.BUILDER ?: 'auto', waitSeconds: waitSec) { String where -> checkHere(cfg, where) }
                    }
                }
            }
        }
    }
}

Map normalize(Map a) {
    if (!a.check) { error('sandcorpPrCheck: 인자 check 가 없다 — README.md') }
    return [
        name          : a.name ?: '',
        check         : a.check,
        buildSsh      : a.buildSsh ? true : false,
        migrationGuard: a.migrationGuard == null ? true : (a.migrationGuard ? true : false),
        target        : a.target ?: 'main',
        macWaitSeconds: (a.macWaitSeconds ?: Nodes.MAC_WAIT_SECONDS) as int,
        timeoutMinutes: (a.timeoutMinutes ?: 60) as int,
    ]
}

// 이 노드에서 PR(머지 결과)을 체크아웃하고 파괴적 migrate 검사 → check. where = macos | home.
void checkHere(Map cfg, String where) {
    checkout scm
    String head = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
    String pr = env.CHANGE_ID ? "PR #${env.CHANGE_ID}" : (env.BRANCH_NAME ?: '')
    currentBuild.description = "${pr} · ${head.take(12)} · ${where}"
    echo "== PR 검사 노드: ${where} (${env.NODE_NAME}), ${pr} → ${env.CHANGE_TARGET ?: cfg.target}"

    if (cfg.migrationGuard) {
        stage('파괴적 migrate') {
            String base = baseRev(cfg)
            String tmp = pwd(tmp: true)
            writeFile file: "${tmp}/migrate-guard.py", text: libraryResource('sandcorp/migrate-guard.py')
            writeFile file: "${tmp}/migrate-guard.sh", text: libraryResource('sandcorp/migrate-guard.sh')
            withEnv(["GUARD_PY=${tmp}/migrate-guard.py"]) {
                sh "bash '${tmp}/migrate-guard.sh' '${base}'"
            }
        }
    }

    stage('검사') {
        withEnv(["SANDCORP_BUILT_ON=${where}", 'SANDCORP_PR_CHECK=1']) {
            if (cfg.buildSsh) {
                withCredentials([sshUserPrivateKey(credentialsId: Nodes.GIT_SSH_CREDENTIALS, keyFileVariable: 'GIT_SSH_KEY')]) {
                    sh cfg.check
                }
            } else {
                sh cfg.check
            }
        }
    }
}

// PR 이 더한 것을 가를 기준: 받아 둔 origin/<대상>(잡 refspec 이 받는다), 없으면 머지 커밋의 첫 부모.
String baseRev(Map cfg) {
    String target = env.CHANGE_TARGET ?: cfg.target
    String ref = "refs/remotes/origin/${target}"
    if (sh(returnStatus: true, script: "git rev-parse -q --verify '${ref}^{commit}' >/dev/null") == 0) { return ref }
    if (sh(returnStatus: true, script: "git rev-parse -q --verify 'HEAD^2' >/dev/null") == 0) { return 'HEAD^1' }
    error("sandcorpPrCheck: 기준(origin/${target})을 찾지 못했다 — 잡에 refspec +refs/heads/${target}:refs/remotes/@{remote}/${target} 를 더한다(README 「PR 검사」)")
}
