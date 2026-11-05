import groovy.json.JsonOutput
import kr.sandcorp.ci.FailureComment
import kr.sandcorp.ci.Nodes
import kr.sandcorp.ci.PrCheckSkip
import kr.sandcorp.ci.StepScript

/**
 * PR 검사 파이프라인 — 저장소 Jenkinsfile.pr 은 이것 한 번만 부른다. 잡은 PR 만 발견하는 멀티브랜치(README 「PR 검사」).
 *
 *   빌드 노드(home 먼저, 못 잡으면 macos — Nodes.PR_CHECK_ORDER. prefer: 'macos' 면 거꾸로)에서 PR 머지 결과 체크아웃 → 파괴적 migrate 검사 → check
 *
 * 결과는 github-branch-source 가 PR head 커밋 상태(continuous-integration/jenkins/pr-merge)로 올린다 —
 * 작업자는 wait-ci.py 로 그것을 기다린 뒤 병합한다. 새 push 가 오면 돌던 빌드는 끊는다.
 * 「파괴적 migrate」·「검사」가 실패하면 실패 요지를 PR 댓글로 단다(runLogged) — 작업자는 Jenkins 콘솔을 못 읽는다.
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
            choice(name: 'BUILDER', choices: ['auto', 'home', 'macos'], description: "auto=${cfg.order[0]} 먼저, 못 잡으면 ${cfg.order[1]}. 시험용으로 강제 선택")
            string(name: 'WAIT_SECONDS', defaultValue: "${cfg.waitSeconds}", description: "auto에서 ${cfg.order[0]} 실행기를 기다리는 시간(초). 넘으면 ${cfg.order[1]}")
        }

        stages {
            stage('PR 검사') {
                steps {
                    script {
                        int waitSec = (params.WAIT_SECONDS ?: "${cfg.waitSeconds}") as int
                        sandcorpBuildNode(builder: params.BUILDER ?: 'auto', order: cfg.order, waitSeconds: waitSec) { String where ->
                            checkHere(cfg, where)
                        }
                    }
                }
            }
        }
    }
}

Map normalize(Map a) {
    if (!a.check) { error('sandcorpPrCheck: 인자 check 가 없다 — README.md') }
    String prefer = a.prefer ?: 'home'
    if (!(prefer in ['home', 'macos'])) { error("sandcorpPrCheck: prefer 는 home|macos 다(받은 값 '${prefer}')") }
    List credentials = (a.credentials ?: []).collect { Map c ->
        if (!c.id || !c.variable) { error("sandcorpPrCheck: credentials 항목마다 id·variable 이 있어야 한다: ${c.keySet()}") }
        [id: c.id, variable: c.variable]
    }
    return [
        name          : a.name ?: '',
        check         : a.check,
        buildSsh      : a.buildSsh ? true : false,
        // check 에 더 넘길 Secret text 자격([id:, variable:]) — 예: 검사가 이미지를 굽고 ghcr 에 올리는 저장소(codemit)의 ghcr-push-token.
        credentials   : credentials,
        migrationGuard: a.migrationGuard == null ? true : (a.migrationGuard ? true : false),
        target        : a.target ?: 'main',
        // home 먼저(기본) — check 가 home(Jenkins 컨테이너)에서 다 돌지 못하는 저장소만 prefer: 'macos'.
        order         : prefer == 'home' ? Nodes.PR_CHECK_ORDER : Nodes.DEPLOY_BUILD_ORDER,
        // 먼저 노드를 기다리는 시간. macWaitSeconds 는 mac 먼저였던 때의 이름이라 그대로 받는다.
        waitSeconds   : (a.waitSeconds ?: a.macWaitSeconds ?: Nodes.PR_CHECK_WAIT_SECONDS) as int,
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
                runLogged('파괴적 migrate', where, "bash '${tmp}/migrate-guard.sh' '${base}'")
            }
        }
    }

    stage('검사') {
        withEnv(["SANDCORP_BUILT_ON=${where}", 'SANDCORP_PR_CHECK=1']) {
            List creds = []
            if (cfg.buildSsh) {
                creds.add(sshUserPrivateKey(credentialsId: Nodes.GIT_SSH_CREDENTIALS, keyFileVariable: 'GIT_SSH_KEY'))
            }
            for (Map c in cfg.credentials) {
                creds.add(string(credentialsId: c.id, variable: c.variable))
            }
            if (creds) {
                withCredentials(creds) { runLogged('검사', where, cfg.check) }
            } else {
                runLogged('검사', where, cfg.check)
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

// 단계 명령을 `sh` 와 같게(sh -xe) 돌리되 출력을 파일에도 남긴다(tee). 실패하면 PR 에 댓글을 달고 같은 실패로 끝낸다.
// 성공 경로는 출력이 파일에도 한 벌 더 쓰이는 것 말고 그대로다.
void runLogged(String stageName, String where, String script) {
    String tmp = pwd(tmp: true)
    String key = stageName == '검사' ? 'check' : 'migrate'
    String cmd = StepScript.path(tmp, 'pr-check', key)
    String log = "${tmp}/pr-check-${key}.log"
    StepScript.guard(cmd, script)
    writeFile file: cmd, text: script
    int rc = sh(returnStatus: true, script: """#!/bin/bash
sh -xe '${cmd}' 2>&1 | tee '${log}'
exit "\${PIPESTATUS[0]}"
""")
    if (rc != 0) {
        commentFailure(stageName, where, rc, log)
        error("${stageName}: script returned exit code ${rc}")
    }
}

// PR 에 실패 댓글 하나(kr.sandcorp.ci.FailureComment). PR 빌드가 아니면 하지 않는다. 못 달아도 경고만 — 빌드 결과는 단계 실패 그대로.
// GitHub 자격은 배포의 재검사 판정과 같은 Nodes.GITHUB_API_CREDENTIALS(멀티브랜치 소스·커밋 상태의 그것). 토큰은 환경변수로만 넘긴다.
void commentFailure(String stageName, String where, int rc, String log) {
    if (!env.CHANGE_ID) { return }
    try {
        String repo = FailureComment.repoFromChangeUrl(env.CHANGE_URL) ?: PrCheckSkip.repoSlug(scm.userRemoteConfigs[0].url)
        if (!repo) { echo "경고: PR 댓글 생략 — GitHub 저장소를 모른다(${env.CHANGE_URL})"; return }
        String head = FailureComment.prHead(sh(returnStdout: true, script: 'git rev-list --parents -n 1 HEAD'))
        String output = sh(returnStdout: true, script: "tail -n ${FailureComment.READ_LINES} '${log}' 2>/dev/null || true")
        String body = FailureComment.body(stage: stageName, head: head, where: where, build: env.BUILD_NUMBER,
            url: env.BUILD_URL, code: rc, output: output)
        String json = "${pwd(tmp: true)}/pr-check-comment.json"
        writeFile file: json, text: JsonOutput.toJson([body: body])
        withCredentials([usernamePassword(credentialsId: Nodes.GITHUB_API_CREDENTIALS, usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
            withEnv(["GH_URL=https://api.github.com/repos/${repo}/issues/${env.CHANGE_ID}/comments", "GH_BODY=${json}"]) {
                int st = sh(returnStatus: true, script: '''#!/bin/sh
curl -fsS --max-time 15 -o /dev/null -X POST -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github+json" --data @"$GH_BODY" "$GH_URL"
''')
                echo(st == 0 ? "PR #${env.CHANGE_ID} 에 실패 댓글을 달았다" : "경고: PR 댓글을 못 달았다(curl ${st}) — 자격 ${Nodes.GITHUB_API_CREDENTIALS} 의 issues/pull_requests 쓰기 권한을 본다")
            }
        }
    } catch (err) {
        if (err.getClass().getName().endsWith('FlowInterruptedException')) { throw err }
        echo "경고: PR 댓글을 못 달았다(${err.getMessage()}) — 빌드 결과는 그대로"
    }
}
