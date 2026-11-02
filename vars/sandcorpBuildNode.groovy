import kr.sandcorp.ci.Nodes

/**
 * 빌드 노드 하나를 잡아 body(where) 를 돈다 — sandcorpDeploy(이미지 빌드)와 sandcorpPrCheck(PR 검사)가 같은 규칙을 쓴다.
 *
 *   sandcorpBuildNode(builder: params.BUILDER, waitSeconds: 180) { String where -> ... }   // where = 'macos' | 'home'
 *
 * builder: 'macos'·'home' 은 그 노드만, 'auto'(기본)는 Nodes.MAC_BUILD 를 waitSeconds 기다리고 못 잡으면 Nodes.HOME_BUILD.
 * 넘어가는 건 mac 실행기를 못 잡았을 때뿐이다 — 실행기를 잡은 뒤의 실패(검사·빌드)는 그대로 실패다.
 */
def call(Map opts = [:], Closure body) {
    String b = opts.builder ?: 'auto'
    int waitSec = (opts.waitSeconds ?: Nodes.MAC_WAIT_SECONDS) as int
    if (b == 'macos') {
        node(Nodes.MAC_BUILD) { body('macos') }
        return
    }
    if (b == 'home') {
        node(Nodes.HOME_BUILD) { body('home') }
        return
    }
    // auto: macos 를 잡으면 거기서 끝까지 한다. 대기가 길어지면 감시가 실패해 failFast 로 mac 대기를 걷어 내고
    // (대기열 항목이 취소된다) home 으로 간다.
    env.MAC_ACQUIRED = 'false'
    try {
        parallel(
            failFast: true,
            'macos': {
                node(Nodes.MAC_BUILD) {
                    env.MAC_ACQUIRED = 'true'
                    body('macos')
                }
            },
            '대기 감시': {
                int waited = 0
                while (env.MAC_ACQUIRED != 'true' && waited < waitSec) {
                    sleep time: 5, unit: 'SECONDS'
                    waited += 5
                }
                if (env.MAC_ACQUIRED != 'true') {
                    error("macos 실행기를 ${waitSec}초 안에 못 잡았다")
                }
            }
        )
    } catch (err) {
        if (env.MAC_ACQUIRED == 'true') { throw err }
        echo "macos를 못 잡아 home에서 돈다: ${err.getMessage()}"
        node(Nodes.HOME_BUILD) { body('home') }
    }
}
