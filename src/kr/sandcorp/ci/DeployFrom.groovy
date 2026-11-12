package kr.sandcorp.ci

/**
 * 배포 브랜치 인자 확인. Jenkins API 를 부르지 않는 순수 함수라 tests/DeployFromTest.groovy 가 바로 돌린다.
 *
 * GitHub Actions 가 검사·이미지 빌드를 마쳐 GHCR 에 공개한 이미지로만 Jenkins 가 배포한다.
 */
class DeployFrom implements Serializable {

    static final String ACTIONS = 'actions'
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

}
