package kr.sandcorp.ci

/**
 * sandcorpDeploy 인자 extraCheckouts — 배포(oracle) 단계에서 다른 저장소를 고정 sha 로 받는다(README 「다른 저장소 받기」).
 *
 * 꼴: [[url: 'git@github.com:<owner>/<repo>.git', refFile: 'deploy/x.ref', dir: 'x-data', credentialsId: '<SSH 자격 id>'], ...]
 *   - refFile: 배포할 저장소 안 파일. 40자 소문자 sha 한 줄(끝 줄바꿈 하나는 된다).
 *   - dir: 워크스페이스 바로 아래 한 칸짜리 이름. 매번 지우고(Jenkins deleteDir) 다시 받는다.
 * 넘기지 않으면(null·빈 목록) 빈 목록 — 배포는 지금과 같다.
 * 형식이 틀리면 IllegalArgumentException(배포 전에 멈춘다).
 */
class ExtraCheckouts implements Serializable {
    static final Set<String> KEYS = ['url', 'refFile', 'dir', 'credentialsId'] as Set
    // GitHub ssh 만 받는다 — known_hosts 를 GitHub 공개 호스트 키로 고정하기 때문(resources/sandcorp/github_known_hosts).
    static final String URL_RE = /git@github\.com:[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+\.git/
    // 저장소 안 상대 경로: 칸마다 점으로 시작하지 않는 이름(`..`·숨은 칸·절대 경로 없음).
    static final String REF_FILE_RE = /[A-Za-z0-9_][A-Za-z0-9_.-]*(\/[A-Za-z0-9_][A-Za-z0-9_.-]*)*/
    // 워크스페이스 바로 아래 한 칸(슬래시·점으로 시작·`..` 없음).
    static final String DIR_RE = /[A-Za-z0-9_][A-Za-z0-9_.-]*/
    static final String CREDENTIALS_RE = /[A-Za-z0-9_.-]+/
    static final String SHA_RE = /[0-9a-f]{40}/

    static List<Map> normalize(Object raw) {
        if (raw == null) { return [] }
        if (!(raw instanceof List)) { throw new IllegalArgumentException('extraCheckouts 는 목록이어야 한다: [[url:, refFile:, dir:, credentialsId:], ...]') }
        List<Map> out = []
        Set<String> dirs = [] as Set
        ((List) raw).each { Object item ->
            if (!(item instanceof Map)) { throw new IllegalArgumentException("extraCheckouts 항목은 [url:, refFile:, dir:, credentialsId:] 이어야 한다: ${item}") }
            Map m = (Map) item
            Set<String> keys = m.keySet().collect { it.toString() } as Set
            if (keys != KEYS) { throw new IllegalArgumentException("extraCheckouts 항목의 키는 ${KEYS.sort()} 이어야 한다: ${keys.sort()}") }
            String url = m.url?.toString() ?: ''
            String refFile = m.refFile?.toString() ?: ''
            String dir = m.dir?.toString() ?: ''
            String cred = m.credentialsId?.toString() ?: ''
            if (!(url ==~ URL_RE)) { throw new IllegalArgumentException("extraCheckouts url 은 git@github.com:<owner>/<repo>.git 꼴이어야 한다: ${url}") }
            if (!(refFile ==~ REF_FILE_RE)) { throw new IllegalArgumentException("extraCheckouts refFile 은 저장소 안 상대 경로여야 한다: ${refFile}") }
            if (!(dir ==~ DIR_RE)) { throw new IllegalArgumentException("extraCheckouts dir 은 워크스페이스 바로 아래 한 칸 이름이어야 한다: ${dir}") }
            if (!(cred ==~ CREDENTIALS_RE)) { throw new IllegalArgumentException("extraCheckouts credentialsId 가 비었거나 이상하다: ${cred}") }
            if (!dirs.add(dir)) { throw new IllegalArgumentException("extraCheckouts dir 이 겹친다: ${dir}") }
            out << [url: url, refFile: refFile, dir: dir, credentialsId: cred]
        }
        return out
    }

    /** refFile 내용 → sha. 40자 소문자 sha 한 줄(끝 줄바꿈 하나 허용)이 아니면 IllegalArgumentException. */
    static String parseRef(String refFile, String text) {
        String t = text ?: ''
        if (t.endsWith('\n')) { t = t.substring(0, t.length() - 1) }
        if (!(t ==~ SHA_RE)) {
            throw new IllegalArgumentException("${refFile} 는 40자 소문자 커밋 sha 한 줄이어야 한다(${t.length()}자)")
        }
        return t
    }

    /** 받기 명령. 인자는 모두 검증된 값이라 작은따옴표로 충분하다. */
    static String command(String script, String knownHosts, Map e, String sha) {
        if (!(sha ==~ SHA_RE)) { throw new IllegalArgumentException("sha 가 아니다: ${sha}") }
        return "SANDCORP_KNOWN_HOSTS='${knownHosts}' bash '${script}' '${e.dir}' '${e.url}' '${sha}'"
    }
}
