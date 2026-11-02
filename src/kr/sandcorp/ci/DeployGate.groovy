package kr.sandcorp.ci

/**
 * 이 빌드가 배포해야 하는지 정한다. Jenkins API 를 부르지 않는 순수 함수라 tests/DeployGateTest.groovy 가 바로 돌린다.
 *
 * GitHub push 웹훅은 작업 브랜치의 생성·push·삭제에도 이 잡을 깨우고, 잡은 항상 main 을 받는다(웹훅 페이로드의
 * ref 는 githubPush 트리거가 넘겨주지 않는다). 그래서 「main 이 지난 배포 뒤로 움직였는가」로 거른다 —
 * 작업 브랜치 push 는 main 을 움직이지 않으므로 NOT_BUILT 로 끝난다. 같은 이유로, 배포가 도는 동안 들어온 두 번째
 * push 이벤트가 대기열에 쌓은 빌드(11/01 portfolio #96·#97)도 앞 빌드가 끝난 뒤 같은 sha 라서 건너뛴다.
 */
class DeployGate implements Serializable {

    /**
     * @param a.mainSha        지금 main 의 커밋 sha
     * @param a.branch         체크아웃이 알려 준 브랜치(origin/main 등). 비면 확인 안 함
     * @param a.deployBranch   배포 브랜치 이름(main)
     * @param a.rollbackSha    ROLLBACK_SHA 매개변수(있으면 언제나 배포)
     * @param a.manual         사람이 「Build Now」로 돌렸는가
     * @param a.lastSuccess    직전 성공 빌드의 [main: sha] 또는 null
     * @param a.lastAttempt    배포까지 간 가장 최근 빌드의 [main: sha, result: 'SUCCESS'|'FAILURE'] 또는 null
     * @return [deploy: boolean, reason: String]
     */
    static Map decide(Map a) {
        if (a.rollbackSha) {
            return [deploy: true, reason: "ROLLBACK_SHA=${short12(a.rollbackSha)} 수동 되돌리기".toString()]
        }
        String b = a.branch ?: ''
        String want = a.deployBranch ?: 'main'
        if (b && !(b == want || b.endsWith('/' + want))) {
            return [deploy: false, reason: "배포 브랜치(${want})가 아니다: ${b}".toString()]
        }
        if (!a.mainSha) {
            return [deploy: false, reason: 'main 커밋을 알 수 없다']
        }
        if (a.lastSuccess?.main == a.mainSha) {
            return [deploy: false, reason: "이미 배포에 성공한 sha ${short12(a.mainSha)} — main 이 움직이지 않았다(작업 브랜치 push 등)".toString()]
        }
        // 실패한 sha 를 push 마다 다시 배포하면, 깨진 커밋이 작업 브랜치 push 때마다 배포→자동 롤백을 되풀이한다.
        // 사람이 「Build Now」로 돌릴 때만 같은 sha 를 다시 시도한다.
        if (!a.manual && a.lastAttempt?.main == a.mainSha && a.lastAttempt?.result != 'SUCCESS') {
            return [deploy: false, reason: "직전 배포가 실패한 sha ${short12(a.mainSha)} — 다시 하려면 「Build Now」".toString()]
        }
        return [deploy: true, reason: "main ${short12(a.mainSha)} 배포".toString()]
    }

    static String short12(String sha) {
        return sha ? (sha.length() > 12 ? sha.substring(0, 12) : sha) : ''
    }
}
