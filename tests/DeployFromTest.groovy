// 배포 브랜치 계약.
import kr.sandcorp.ci.DeployFrom

assert DeployFrom.branch('', '') == 'main'
assert DeployFrom.branch('production', null) == 'production'
assert DeployFrom.ghcrRepo('ghcr.io/sandcorporation/portfolio') == 'ghcr.io/sandcorporation/portfolio'
assert DeployFrom.ghcrRepo('ghcr.io/sandcorporation/sandcorp-flow/api') == 'ghcr.io/sandcorporation/sandcorp-flow/api'
for (String invalid in ['', 'docker.io/example/app', 'ghcr.io/example/app:latest', 'ghcr.io/Example/app']) {
    try {
        DeployFrom.ghcrRepo(invalid)
        assert false: "invalid image repository accepted: ${invalid}"
    } catch (IllegalArgumentException ignored) {}
}
try {
    DeployFrom.branch('production', 'main')
    assert false: 'deployFrom 과 branch 가 다르면 실패해야 한다'
} catch (IllegalArgumentException e) {
    assert e.message.contains('하나만')
}
println 'DeployFromTest 통과: 배포 브랜치 계약'
