package kr.sandcorp.ci

/**
 * Jenkins 배포·제어 노드 라벨과 운영 알림 자격 ID.
 */
class Nodes implements Serializable {
    /** 운영 compose 가 도는 곳. 굽지 않고 pull·교체만 한다. */
    static final String DEPLOY = 'oracle'
    /** 배포할 커밋 확인·디스코드 알림처럼 가벼운 일을 하는 곳. */
    static final String CONTROLLER = 'built-in'

    /** GitHub REST 읽기(Username with password, 비밀번호 = 토큰). PR 검사 잡의 스캔 자격과 같은 것 — 재검사 생략 판정이 쓴다. */
    static final String GITHUB_API_CREDENTIALS = 'github-sandcorporation-token'
    /** 디스코드 배포 알림 웹훅(Secret text). URL 은 로그에 찍지 않는다. */
    static final String DISCORD_CREDENTIALS = 'discord-deploy-webhook'

}
