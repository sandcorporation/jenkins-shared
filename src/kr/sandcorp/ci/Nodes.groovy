package kr.sandcorp.ci

/**
 * Jenkins 배포·제어 노드 라벨과 운영 알림 자격 ID.
 */
class Nodes implements Serializable {
    /** 운영 compose 가 도는 곳(기본). 굽지 않고 pull·교체만 한다. 저장소가 sandcorpDeploy(deployNode:)로 바꿀 수 있다. */
    static final String DEPLOY = 'oracle'
    /** 배포할 커밋 확인·디스코드 알림처럼 가벼운 일을 하는 곳. */
    static final String CONTROLLER = 'built-in'

    /** GitHub REST 읽기(Username with password, 비밀번호 = 토큰). PR 검사 잡의 스캔 자격과 같은 것 — 재검사 생략 판정이 쓴다. */
    static final String GITHUB_API_CREDENTIALS = 'github-sandcorporation-token'
    /** 디스코드 배포 알림 웹훅(Secret text). URL 은 로그에 찍지 않는다. */
    static final String DISCORD_CREDENTIALS = 'discord-deploy-webhook'

    /**
     * sandcorpDeploy 인자 deployNode 를 배포 노드 라벨로. 비면 DEPLOY(oracle) — 지금까지와 같다.
     * 제어 노드(built-in)에서는 배포하지 않는다. 라벨은 영문·숫자·_.- 64자까지(Jenkins 라벨 식은 받지 않는다).
     */
    static String deployNode(Object raw) {
        String v = raw == null ? '' : raw.toString().trim()
        if (!v) { return DEPLOY }
        if (v == CONTROLLER) { throw new IllegalArgumentException("deployNode 는 ${CONTROLLER} 일 수 없다 — 제어 노드에서는 배포하지 않는다") }
        if (!(v ==~ /[A-Za-z0-9][A-Za-z0-9_.-]{0,63}/)) { throw new IllegalArgumentException("deployNode 라벨 모양이 틀렸다: ${v}") }
        return v
    }
}
