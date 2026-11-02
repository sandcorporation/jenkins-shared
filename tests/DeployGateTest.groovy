// DeployGate.decide 시험. Jenkins 없이 groovy 로 돈다: bash tests/run.sh (또는 tests/groovy.sh)
import kr.sandcorp.ci.DeployGate

int n = 0
def ok = { String what, Map args, boolean want, String reasonHas ->
    def r = DeployGate.decide(args)
    assert r.deploy == want: "${what}: deploy=${r.deploy} (${r.reason})"
    assert r.reason.contains(reasonHas): "${what}: reason='${r.reason}' 에 '${reasonHas}' 없음"
    n++
}

def A = 'a' * 40
def B = 'b' * 40

ok('main 이 움직였다', [mainSha: B, branch: 'origin/main', lastSuccess: [main: A], lastAttempt: [main: A, result: 'SUCCESS']], true, 'main bbbbbbbbbbbb 배포')
ok('처음 배포(이전 기록 없음)', [mainSha: A, branch: 'origin/main'], true, '배포')
ok('작업 브랜치 push — main 그대로', [mainSha: A, branch: 'origin/main', lastSuccess: [main: A], lastAttempt: [main: A, result: 'SUCCESS']], false, '이미 배포에 성공한')
ok('대기열 중복 빌드 — 앞 빌드가 같은 sha 성공', [mainSha: A, branch: 'origin/main', lastSuccess: [main: A]], false, '이미 배포에 성공한')
ok('사람이 돌려도 성공한 sha 는 건너뜀', [mainSha: A, manual: true, lastSuccess: [main: A]], false, '이미 배포에 성공한')
ok('ROLLBACK_SHA 는 언제나 배포', [mainSha: A, rollbackSha: B, lastSuccess: [main: A]], true, 'ROLLBACK_SHA=bbbbbbbbbbbb')
ok('실패한 sha 를 push 가 다시 깨우면 건너뜀', [mainSha: B, lastSuccess: [main: A], lastAttempt: [main: B, result: 'FAILURE']], false, '직전 배포가 실패한')
ok('실패한 sha 를 사람이 다시 돌리면 배포', [mainSha: B, manual: true, lastSuccess: [main: A], lastAttempt: [main: B, result: 'FAILURE']], true, '배포')
ok('배포 브랜치가 아니다', [mainSha: A, branch: 'origin/feature-x'], false, '배포 브랜치(main)가 아니다')
ok('브랜치 이름이 main 그대로', [mainSha: A, branch: 'main'], true, '배포')
ok('배포 브랜치를 바꿀 수 있다', [mainSha: A, branch: 'origin/release', deployBranch: 'release'], true, '배포')
ok('main sha 를 모름', [mainSha: null, branch: 'origin/main'], false, '알 수 없다')

println "DeployGate 시험: 통과 ${n}"
