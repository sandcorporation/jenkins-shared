package kr.sandcorp.ci

/**
 * Jenkins 노드 라벨과 자격 ID — 이 라이브러리에서 노드·자격 이름을 적는 곳은 여기 하나뿐이다.
 *
 * 노드를 바꾸려면 이 파일만 고친다. 예: `laurio-staging` 라벨을 지우기 전에 HOME_BUILD 를 'built-in' 으로 바꿔
 * main 에 병합하면, 이 라이브러리를 쓰는 모든 저장소의 다음 빌드부터 바뀐다(저장소 Jenkinsfile 은 그대로).
 */
class Nodes implements Serializable {
    /** 이미지를 먼저 굽는 곳(M4, arm64 네이티브). 실행기 1개를 여러 잡이 나눠 쓴다. */
    static final String MAC_BUILD = 'macos'
    /** mac 실행기를 MAC_WAIT_SECONDS 안에 못 잡으면 굽는 곳(home 의 Jenkins built-in 노드, QEMU 로 arm64). */
    static final String HOME_BUILD = 'laurio-staging'
    /** 운영 compose 가 도는 곳. 굽지 않고 pull·교체만 한다. */
    static final String DEPLOY = 'oracle'
    /** 배포할 커밋 확인·디스코드 알림처럼 가벼운 일을 하는 곳. */
    static final String CONTROLLER = 'built-in'

    /** mac 실행기를 기다리는 기본 시간(초). 넘으면 HOME_BUILD 로 간다. */
    static final int MAC_WAIT_SECONDS = 180

    /** ghcr 쓰기 토큰(Secret text). */
    static final String GHCR_PUSH_CREDENTIALS = 'ghcr-push-token'
    /** 비공개 git 의존성용 SSH 키(sshUserPrivateKey). */
    static final String GIT_SSH_CREDENTIALS = 'github-farrar142'
    /** GitHub REST 읽기(Username with password, 비밀번호 = 토큰). PR 검사 잡의 스캔 자격과 같은 것 — 재검사 생략 판정이 쓴다. */
    static final String GITHUB_API_CREDENTIALS = 'github-sandcorporation-token'
    /** 디스코드 배포 알림 웹훅(Secret text). URL 은 로그에 찍지 않는다. */
    static final String DISCORD_CREDENTIALS = 'discord-deploy-webhook'
}
