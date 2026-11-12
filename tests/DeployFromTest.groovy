// 배포 브랜치 계약.
import kr.sandcorp.ci.DeployFrom

assert DeployFrom.branch('', '') == 'main'
assert DeployFrom.branch('production', null) == 'production'
try {
    DeployFrom.branch('production', 'main')
    assert false: 'deployFrom 과 branch 가 다르면 실패해야 한다'
} catch (IllegalArgumentException e) {
    assert e.message.contains('하나만')
}
println 'DeployFromTest 통과: 배포 브랜치 계약'
