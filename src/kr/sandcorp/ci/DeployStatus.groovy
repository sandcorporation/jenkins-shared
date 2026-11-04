package kr.sandcorp.ci

/**
 * 배포 결과를 GitHub 커밋 상태(context sandcorp/deploy)로 남길 때 무엇을 쓸지 정한다. Jenkins·GitHub 를 부르지 않는 순수 함수라
 * tests/DeployStatusTest.groovy 가 바로 돌린다. 작업자(work)는 Jenkins API 자격이 없어 wait-deploy.py 가 이 상태를 gh 로 읽는다 —
 * context·상태 값·설명 앞머리(「빌드 #N · 」)를 바꾸면 거기도 고친다.
 *
 * - 시작: pending 「빌드 #N · 배포 중」
 * - 끝: success | failure 「빌드 #N · <결과 한 줄>」(140자 안). 중단·시간 초과(ABORTED)도 failure 로 끝맺는다 — pending 으로 남기지 않는다.
 * - 건너뜀(gate 가 NOT_BUILT): 그 sha 에 이미 끝난 상태(success|failure|error)가 있으면 덮지 않는다. 없거나 pending 이면 남긴다.
 * - target_url 은 비운다(빌드 URL 은 내부 주소다).
 */
class DeployStatus implements Serializable {

    static final String CONTEXT = 'sandcorp/deploy'
    static final int MAX_DESCRIPTION = 140
    /** 실패 댓글 제목 앞머리. wait-deploy.py 가 이것과 배포 sha 앞 12자로 댓글을 찾는다. */
    static final String COMMENT_PREFIX = '배포 실패 — '

    static Map pending(def build) {
        return payload('pending', "빌드 #${build} · 배포 중")
    }

    /**
     * @param a.build         빌드 번호
     * @param a.result        currentBuild.currentResult(SUCCESS|FAILURE|ABORTED|UNSTABLE…)
     * @param a.builtOn       이미지를 구운 노드(macos|home) 또는 비움
     * @param a.checkSkipped  재검사를 생략했으면 그 근거(비면 안 생략)
     * @param a.failedStage   실패한 단계(검사|이미지 굽기|배포) 또는 비움
     * @param a.rolledBackTo  자동 롤백한 sha(성공)
     * @param a.rollbackFailed 자동 롤백도 실패했는가
     * @param a.took          걸린 시간(사람이 읽는 문자열) 또는 비움
     */
    static Map finish(Map a) {
        String result = (a.result ?: 'FAILURE') as String
        List<String> parts = []
        String state
        if (result == 'SUCCESS') {
            state = 'success'
            parts << '성공'
            if (a.checkSkipped) { parts << '검사 생략' }
        } else {
            state = 'failure'
            if (a.rollbackFailed) {
                parts << '실패 · 자동 롤백도 실패 — 운영을 본다'
            } else if (a.rolledBackTo) {
                parts << "실패 → ${short12(a.rolledBackTo as String)} 자동 롤백".toString()
            } else if (result == 'ABORTED') {
                parts << '중단(시간 초과 포함)'
            } else {
                parts << (a.failedStage ? "${a.failedStage} 실패".toString() : "실패(${result})".toString())
            }
        }
        if (a.builtOn) { parts << "빌드 ${a.builtOn}".toString() }
        if (a.took) { parts << (a.took as String) }
        return payload(state, "빌드 #${a.build} · " + parts.join(' · '))
    }

    /**
     * gate 가 건너뛴 빌드. 그 main sha 에 남길 상태 또는 null(남기지 않는다).
     * @param a.build    빌드 번호
     * @param a.kind     DeployGate.decide 의 kind(deployed|failed|…)
     * @param a.existing 그 sha 의 sandcorp/deploy 최신 state 또는 null
     */
    static Map onSkip(Map a) {
        if (a.existing && a.existing != 'pending') { return null }
        if (a.kind == 'deployed') { return payload('success', "빌드 #${a.build} · 건너뜀 · 이미 배포한 sha") }
        if (a.kind == 'failed') { return payload('failure', "빌드 #${a.build} · 건너뜀 · 직전 배포가 실패한 sha — 다시 하려면 Build Now") }
        return null
    }

    /** GET commits/{sha}/status 의 statuses 에서 이 context 의 state(없으면 null). 응답은 문맥마다 최신 하나다. */
    static String stateIn(List statuses) {
        Map s = (statuses ?: []).find { Map it -> it.context == CONTEXT } as Map
        return s ? (s.state as String) : null
    }

    static Map payload(String state, def description) {
        String d = description as String
        if (d.length() > MAX_DESCRIPTION) { d = d.substring(0, MAX_DESCRIPTION - 1) + '…' }
        // target_url 은 넣지 않는다(내부 Jenkins 주소가 공개 저장소에 남는다).
        return [state: state, description: d, context: CONTEXT]
    }

    static String short12(String sha) {
        return sha ? (sha.length() > 12 ? sha.substring(0, 12) : sha) : ''
    }
}
