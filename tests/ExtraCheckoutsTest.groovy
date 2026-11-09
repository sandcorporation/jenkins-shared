// ExtraCheckouts(sandcorpDeploy 인자 extraCheckouts) 시험. Jenkins 없이 groovy 로 돈다: bash tests/run.sh
import kr.sandcorp.ci.ExtraCheckouts

int n = 0
def eq = { what, got, want -> assert got == want: "${what}: ${got} != ${want}"; n++ }
def refuses = { what, Closure c -> try { c(); assert false: "${what}: 통과했다" } catch (IllegalArgumentException ignored) { n++ } }

Map good = [url: 'git@github.com:sandcorporation/sandcorp-promptlab-problems.git', refFile: 'deploy/problems.ref',
            dir: 'problems-data', credentialsId: 'promptlab-problems-deploy-key']
String sha = '0123456789abcdef0123456789abcdef01234567'

// 인자 없음 = 기존 동작(받을 것 없음 — deployHere 는 빈 목록이면 아무 단계도 만들지 않는다)
eq('인자 없음', ExtraCheckouts.normalize(null), [])
eq('빈 목록', ExtraCheckouts.normalize([]), [])

// 인자 있음 = 항목마다 받기 단계 하나
eq('항목 하나', ExtraCheckouts.normalize([good]), [good])
eq('항목 둘', ExtraCheckouts.normalize([good, good + [dir: 'other']]).collect { it.dir }, ['problems-data', 'other'])
String cmd = ExtraCheckouts.command('/w@tmp/sandcorp-extra-checkout.sh', '/w@tmp/sandcorp-known-hosts', good, sha)
eq('받기 명령', cmd, "SANDCORP_KNOWN_HOSTS='/w@tmp/sandcorp-known-hosts' bash '/w@tmp/sandcorp-extra-checkout.sh' 'problems-data' '${good.url}' '${sha}'".toString())

// 잘못된 인자
refuses('목록 아님') { ExtraCheckouts.normalize(good) }
refuses('항목이 목록') { ExtraCheckouts.normalize([[good.url, good.refFile, good.dir, good.credentialsId]]) }
refuses('키 빠짐') { ExtraCheckouts.normalize([good.findAll { it.key != 'credentialsId' }]) }
refuses('모르는 키') { ExtraCheckouts.normalize([good + [branch: 'main']]) }
refuses('https url') { ExtraCheckouts.normalize([good + [url: 'https://github.com/a/b.git']]) }
refuses('다른 호스트') { ExtraCheckouts.normalize([good + [url: 'git@gitlab.com:a/b.git']]) }
refuses('url 에 따옴표') { ExtraCheckouts.normalize([good + [url: "git@github.com:a/b'.git"]]) }
['..', '.', '/abs', 'a/b', '.hidden', '', "a'b", 'a b'].each { d -> refuses("dir ${d}") { ExtraCheckouts.normalize([good + [dir: d]]) } }
['/etc/passwd', '../x.ref', 'deploy/../x', '.ref', ''].each { r -> refuses("refFile ${r}") { ExtraCheckouts.normalize([good + [refFile: r]]) } }
refuses('자격 빔') { ExtraCheckouts.normalize([good + [credentialsId: '']]) }
refuses('dir 겹침') { ExtraCheckouts.normalize([good, good]) }

// 잘못된 ref = 실패
eq('sha 한 줄', ExtraCheckouts.parseRef('deploy/problems.ref', sha), sha)
eq('끝 줄바꿈 하나', ExtraCheckouts.parseRef('deploy/problems.ref', sha + '\n'), sha)
['', '\n', sha.substring(0, 12), sha.toUpperCase(), sha + '0', ' ' + sha, sha + '\n\n', sha + '\r\n',
 sha + '\n' + sha, 'main', 'TODO: 데이터 저장소가 생기면 sha'].each { t ->
    refuses("ref ${t.take(20)}") { ExtraCheckouts.parseRef('deploy/problems.ref', t) }
}
refuses('명령에 sha 아닌 값') { ExtraCheckouts.command('/s', '/k', good, 'main') }

println "ExtraCheckoutsTest 통과: ${n}건"
