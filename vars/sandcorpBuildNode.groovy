import kr.sandcorp.ci.Nodes

/**
 * 빌드 노드 하나를 잡아 body(where) 를 돈다 — sandcorpDeploy(이미지 빌드)와 sandcorpPrCheck(PR 검사)가 같은 규칙을 쓴다.
 *
 *   sandcorpBuildNode(builder: params.BUILDER, order: Nodes.PR_CHECK_ORDER, waitSeconds: 180) { String where -> ... }
 *   // where = 'macos' | 'home'
 *
 * builder: 'macos'·'home' 은 그 노드만. 'auto'(기본)는 order[0] 을 waitSeconds 기다리고 못 잡으면 order[1].
 * order 기본은 Nodes.DEPLOY_BUILD_ORDER(macos → home), PR 검사는 Nodes.PR_CHECK_ORDER(home → macos).
 * 넘어가는 건 먼저 노드의 실행기를 못 잡았을 때뿐이다 — 실행기를 잡은 뒤의 실패(검사·빌드)는 그대로 실패다.
 */
def call(Map opts = [:], Closure body) {
    List<String> order = Nodes.order(opts.builder as String, (opts.order ?: Nodes.DEPLOY_BUILD_ORDER) as List<String>)
    int waitSec = (opts.waitSeconds ?: Nodes.MAC_WAIT_SECONDS) as int
    String first = order[0]
    if (order.size() == 1) {
        node(Nodes.label(first)) { body(first) }
        return
    }
    String second = order[1]
    // auto: first 를 잡으면 거기서 끝까지 한다. 대기가 길어지면 감시가 실패해 failFast 로 first 대기를 걷어 내고
    // (대기열 항목이 취소된다) second 로 간다.
    env.BUILD_NODE_ACQUIRED = 'false'
    try {
        parallel(
            failFast: true,
            (first): {
                node(Nodes.label(first)) {
                    env.BUILD_NODE_ACQUIRED = 'true'
                    body(first)
                }
            },
            '대기 감시': {
                int waited = 0
                while (env.BUILD_NODE_ACQUIRED != 'true' && waited < waitSec) {
                    sleep time: 5, unit: 'SECONDS'
                    waited += 5
                }
                if (env.BUILD_NODE_ACQUIRED != 'true') {
                    error("${first} 실행기를 ${waitSec}초 안에 못 잡았다")
                }
            }
        )
    } catch (err) {
        if (env.BUILD_NODE_ACQUIRED == 'true') { throw err }
        echo "${first}를 못 잡아 ${second}에서 돈다: ${err.getMessage()}"
        node(Nodes.label(second)) { body(second) }
    }
}
