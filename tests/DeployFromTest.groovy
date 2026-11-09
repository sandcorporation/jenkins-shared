// DeployFrom 시험. Jenkins 없이 groovy 로 돈다: bash tests/run.sh
import kr.sandcorp.ci.DeployFrom

int n = 0
def eq = { String what, def got, def want ->
    assert got == want: "${what}: ${got} != ${want}"
    n++
}
def S = 'a' * 40

// 기본(deployFrom 없음) — 지금 동작 그대로
eq('기본 브랜치는 main', DeployFrom.branch('', ''), 'main')
eq('branch 인자는 그대로', DeployFrom.branch(null, 'release'), 'release')
eq('기본은 BUILD_ON 선택지가 jenkins 하나', DeployFrom.buildOnChoices(''), ['jenkins'])
eq('기본은 언제나 굽는다', DeployFrom.buildsHere('', 'jenkins', ''), true)
eq('기본은 BUILD_ON 값과 상관없이 굽는다', DeployFrom.buildsHere(null, 'actions', ''), true)
eq('기본 되돌리기는 굽지 않는다', DeployFrom.buildsHere('', 'jenkins', S), false)
eq('기본은 기다리지 않는다', DeployFrom.waitsForImages('', 'jenkins', ''), false)
eq('기본 되돌리기도 기다리지 않는다(지금 동작)', DeployFrom.waitsForImages('', 'jenkins', S), false)

// deployFrom: 'production'
eq('배포 브랜치는 deployFrom', DeployFrom.branch('production', null), 'production')
eq('같은 branch 를 함께 줘도 된다', DeployFrom.branch('production', 'production'), 'production')
eq('선택지 첫 값(기본)은 actions', DeployFrom.buildOnChoices('production'), ['actions', 'jenkins'])
eq('actions 면 굽지 않는다', DeployFrom.buildsHere('production', 'actions', ''), false)
eq('actions 면 이미지를 기다린다', DeployFrom.waitsForImages('production', 'actions', ''), true)
eq('파라미터가 아직 없는 첫 빌드(null)도 actions 로 본다', DeployFrom.buildsHere('production', null, ''), false)
eq('비상 길 BUILD_ON=jenkins 는 굽는다', DeployFrom.buildsHere('production', 'jenkins', ''), true)
eq('비상 길은 기다리지 않는다', DeployFrom.waitsForImages('production', 'jenkins', ''), false)
eq('되돌리기는 굽지 않는다', DeployFrom.buildsHere('production', 'jenkins', S), false)
eq('되돌리기는 옛 이미지가 있는지 기다려 본다', DeployFrom.waitsForImages('production', 'jenkins', S), true)

try {
    DeployFrom.branch('production', 'main')
    assert false: 'deployFrom 과 branch 가 다르면 실패해야 한다'
} catch (IllegalArgumentException e) {
    assert e.message.contains('하나만')
    n++
}

println "DeployFrom 시험: 통과 ${n}"
