package kr.sandcorp.ci

/**
 * 승격 브랜치 배포(sandcorpDeploy 인자 deployFrom) 판정. Jenkins API 를 부르지 않는 순수 함수라
 * tests/DeployFromTest.groovy 가 바로 돌린다. README 「승격 브랜치에서 배포(deployFrom)」.
 *
 * deployFrom 을 주면 그 브랜치(production 등)만 배포한다. 그 브랜치는 GitHub Actions 가 main 검사·이미지 빌드를
 * 통과시킨 sha 로만 움직이므로 Jenkins 는 굽지도 검사하지도 않고, 이미지가 레지스트리에 있는지만 짧게 확인한다.
 * 파라미터 BUILD_ON=jenkins 는 Actions 가 못 굽는 날의 비상 길(지금까지처럼 Jenkins 노드에서 굽는다).
 * deployFrom 을 안 주면 모든 판정이 지금 동작(언제나 Jenkins 가 굽는다, 기다리지 않는다) 그대로다.
 */
class DeployFrom implements Serializable {

    static final String ACTIONS = 'actions'
    static final String JENKINS = 'jenkins'
    /** 승격 전에 이미지가 이미 올라가 있어야 하니 짧다 — 레지스트리 전파만 기다린다. */
    static final int IMAGE_WAIT_MINUTES = 5

    /**
     * 배포할 브랜치. deployFrom 이 있으면 그것, 없으면 branch(기본 main).
     * 둘 다 주고 서로 다르면 IllegalArgumentException.
     */
    static String branch(String deployFrom, String branch) {
        if (deployFrom && branch && deployFrom != branch) {
            throw new IllegalArgumentException("deployFrom(${deployFrom})과 branch(${branch})가 다르다 — 하나만 주라".toString())
        }
        return deployFrom ?: (branch ?: 'main')
    }

    /** BUILD_ON 파라미터 선택지. 첫 값이 기본값이다. deployFrom 이 없으면 Jenkins 가 굽는 길 하나뿐이다. */
    static List<String> buildOnChoices(String deployFrom) {
        return deployFrom ? [ACTIONS, JENKINS] : [JENKINS]
    }

    /** 이 빌드에서 Jenkins 노드가 이미지를 굽는가(검사 포함). 되돌리기는 굽지 않는다. */
    static boolean buildsHere(String deployFrom, String buildOn, String rollbackSha) {
        if (rollbackSha) { return false }
        return !deployFrom || buildOn == JENKINS
    }

    /** 배포 전에 레지스트리에서 이미지를 기다리는가 — deployFrom 이고 이 빌드가 굽지 않았을 때만. */
    static boolean waitsForImages(String deployFrom, String buildOn, String rollbackSha) {
        return deployFrom ? !buildsHere(deployFrom, buildOn, rollbackSha) : false
    }
}
