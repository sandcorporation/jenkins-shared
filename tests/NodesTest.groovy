// Jenkins 노드 계약 시험: 이미지 빌드·PR 검사용 노드 라벨이 없어야 한다.
import kr.sandcorp.ci.Nodes

assert Nodes.DEPLOY == 'oracle'
assert Nodes.CONTROLLER == 'built-in'
assert !Nodes.declaredFields*.name.any { it in ['MAC_BUILD', 'HOME_BUILD', 'DEPLOY_BUILD_ORDER', 'PR_CHECK_ORDER'] }
println 'NodesTest 통과: 배포·제어 노드만 선언됨'
