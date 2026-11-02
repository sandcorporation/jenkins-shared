// Nodes(빌드 노드 순서) 시험. Jenkins 없이 groovy 로 돈다: bash tests/run.sh
import kr.sandcorp.ci.Nodes

int n = 0
def eq = { what, got, want -> assert got == want: "${what}: ${got} != ${want}"; n++ }

// 대표 결정(2026-11-02 q11): 배포 굽기는 macos 먼저, PR 검사는 home 먼저.
eq('배포 auto', Nodes.order('auto', Nodes.DEPLOY_BUILD_ORDER), ['macos', 'home'])
eq('PR 검사 auto', Nodes.order('auto', Nodes.PR_CHECK_ORDER), ['home', 'macos'])
eq('빈 builder = auto', Nodes.order(null, Nodes.PR_CHECK_ORDER), ['home', 'macos'])
eq('강제 macos 는 넘어가지 않는다', Nodes.order('macos', Nodes.PR_CHECK_ORDER), ['macos'])
eq('강제 home 은 넘어가지 않는다', Nodes.order('home', Nodes.DEPLOY_BUILD_ORDER), ['home'])

// home 은 built-in(laurio-staging·staging 라벨을 지울 준비).
eq('home 라벨', Nodes.label('home'), 'built-in')
eq('macos 라벨', Nodes.label('macos'), 'macos')
try { Nodes.order('linux', Nodes.PR_CHECK_ORDER); assert false: '모르는 builder 가 통과했다' } catch (IllegalArgumentException ignored) { n++ }
try { Nodes.PR_CHECK_ORDER << 'x'; assert false: '순서 목록이 바뀌었다' } catch (UnsupportedOperationException ignored) { n++ }

println "NodesTest 통과: ${n}건"
