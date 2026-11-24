// 배포 끝 알림(sandcorpDeploy.notifyDiscord) 시험. Jenkins 없이 groovy 로 돈다: bash tests/run.sh
// 컴파일된 vars 스크립트를 그대로 부르고, Jenkins 단계(catchError·withCredentials·sh …)는 binding 의 가짜로 바꾼다.
import kr.sandcorp.ci.DeployNotice
import kr.sandcorp.ci.Nodes

class CredentialNotFoundException extends IOException {
    CredentialNotFoundException(String m) { super(m) }
}

int n = 0
def eq = { what, got, want -> assert got == want: "${what}: ${got} != ${want}"; n++ }

/**
 * creds: Jenkins 에 있는 자격 ID → 웹훅. failing: curl 단계에서 던질 웹훅.
 * 돌려주는 것: posts(보낸 [웹훅, 본문]), echoes(콘솔 줄), warnings(catchError 가 받은 경고), result(빌드 결과).
 */
Map run(Map creds, Set failing = [] as Set) {
    Map rec = [posts: [], echoes: [], warnings: [], result: 'SUCCESS']
    String hook = null
    Map files = [:]
    Binding b = new Binding(
        env: [DEPLOY_SHA: 'a' * 40, BUILD_NUMBER: '7', BUILT_ON: 'node-x', BUILD_URL: 'http://jenkins.example/job/app/7/'],
        currentBuild: [currentResult: 'SUCCESS', durationString: '1 min 2 sec and counting'],
        params: [:],
        catchError: { Map a, Closure c ->
            assert a.buildResult == null && a.stageResult == null
            try { c() } catch (e) { rec.warnings << a.message }
        },
        string: { Map a -> a },
        withCredentials: { List binds, Closure c ->
            String id = binds[0].credentialsId
            if (!creds.containsKey(id)) { throw new CredentialNotFoundException("Could not find credentials entry with ID '${id}'") }
            hook = creds[id]
            try { c() } finally { hook = null }
        },
        writeFile: { Map a -> files[a.file] = a.text },
        sh: { String cmd ->
            assert cmd.contains('"$NOTICE_WEBHOOK"') && !cmd.contains('http')
            if (hook in failing) { throw new RuntimeException("script returned exit code 1") }
            rec.posts << [hook, files['notice-payload.json']]
        },
        echo: { String s -> rec.echoes << s },
    )
    def script = Class.forName('sandcorpDeploy').getConstructor(Binding).newInstance(b)
    script.notifyDiscord([name: 'App'])
    return rec
}

String body = DeployNotice.payload("✅ [App] 배포 SUCCESS — ${'a' * 12} · #7 · 빌드 node-x · 1 min 2 sec · http://jenkins.example/job/app/7/")
Map both = [(Nodes.DISCORD_CREDENTIALS): 'hook-1', (Nodes.NOTICE_CREDENTIALS): 'hook-2']

// 둘째 자격이 있으면 같은 본문이 두 곳에 간다.
Map r = run(both)
eq('두 곳에 같은 본문', r.posts, [['hook-1', body], ['hook-2', body]])
eq('경고 없음', r.warnings, [])
eq('콘솔 줄 없음', r.echoes, [])

// 둘째 자격이 없으면 디스코드만 가고, 경고(catchError) 없이 한 줄만 남는다.
r = run([(Nodes.DISCORD_CREDENTIALS): 'hook-1'])
eq('디스코드만', r.posts, [['hook-1', body]])
eq('없는 자격은 경고 아님', r.warnings, [])
eq('한 줄', r.echoes.size(), 1)
assert r.echoes[0].contains(Nodes.NOTICE_CREDENTIALS) && r.echoes[0].startsWith('알림 건너뜀'); n++

// 첫째(디스코드) 전송이 실패해도 둘째는 가고, 실패는 경고로만 남는다.
r = run(both, ['hook-1'] as Set)
eq('둘째는 간다', r.posts, [['hook-2', body]])
eq('첫째 실패는 경고', r.warnings, ['디스코드 알림 실패 — 배포 결과와 무관하다'])

// 둘째 전송 실패도 경고만.
r = run(both, ['hook-2'] as Set)
eq('첫째는 간다', r.posts, [['hook-1', body]])
eq('둘째 실패는 경고', r.warnings, ['둘째 웹훅 알림 실패 — 배포 결과와 무관하다'])

// 디스코드 자격이 없는 건 지금처럼 경고(optional 아님). 둘째는 그래도 간다.
r = run([(Nodes.NOTICE_CREDENTIALS): 'hook-2'])
eq('디스코드 없음 경고', r.warnings, ['디스코드 알림 실패 — 배포 결과와 무관하다'])
eq('둘째는 간다(디스코드 없음)', r.posts, [['hook-2', body]])

eq('자격 ID', Nodes.NOTICE_CREDENTIALS, 'deploy-notice-webhook')
eq('곳 차례', DeployNotice.TARGETS*.credentialsId, [Nodes.DISCORD_CREDENTIALS, Nodes.NOTICE_CREDENTIALS])

println "DeployNoticeTest 통과: ${n}건"
