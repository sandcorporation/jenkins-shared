package kr.sandcorp.ci

/**
 * sandcorpDeploy 인자 extraCheckouts — 배포(oracle) 단계에서 다른 저장소를 고정 sha 로 받는다(README 「다른 저장소 받기」).
 *
 * 꼴: [[url: 'git@github.com:<owner>/<repo>.git', refFile: 'deploy/x.ref', dir: 'x-data', credentialsId: '<SSH 자격 id>'], ...]
 *   - refFile: 배포할 저장소 안 파일. 40자 소문자 sha 한 줄(끝 줄바꿈 하나는 된다).
 *   - dir: 워크스페이스 바로 아래 한 칸짜리 이름. 매번 지우고(Jenkins deleteDir) 다시 받는다.
 *   - optional(선택, 기본 false): true 면 refFile 이 없거나 틀리거나·sha 가 0 들이거나·자격이 없거나·받기에 실패해도
 *     경고만 남기고 그 항목을 건너뛴다(배포 계속). false 면 그런 경우 배포 실패.
 * 넘기지 않으면(null·빈 목록) 빈 목록 — 배포는 지금과 같다.
 * 형식이 틀리면 IllegalArgumentException(배포 전에 멈춘다).
 */
class ExtraCheckouts implements Serializable {
    static final Set<String> KEYS = ['url', 'refFile', 'dir', 'credentialsId'] as Set
    static final Set<String> OPTIONAL_KEYS = ['optional'] as Set
    // optional 항목에서 「아직 고정한 커밋 없음」을 뜻하는 ref.
    static final String ZERO_SHA = '0' * 40
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
            if (!keys.containsAll(KEYS) || !(OPTIONAL_KEYS + KEYS).containsAll(keys)) {
                throw new IllegalArgumentException("extraCheckouts 항목의 키는 ${KEYS.sort()}(+ 선택 ${OPTIONAL_KEYS.sort()}) 이어야 한다: ${keys.sort()}")
            }
            if (m.containsKey('optional') && !(m.optional instanceof Boolean)) {
                throw new IllegalArgumentException("extraCheckouts optional 은 true·false 여야 한다: ${m.optional}")
            }
            String url = m.url?.toString() ?: ''
            String refFile = m.refFile?.toString() ?: ''
            String dir = m.dir?.toString() ?: ''
            String cred = m.credentialsId?.toString() ?: ''
            if (!(url ==~ URL_RE)) { throw new IllegalArgumentException("extraCheckouts url 은 git@github.com:<owner>/<repo>.git 꼴이어야 한다: ${url}") }
            if (!(refFile ==~ REF_FILE_RE)) { throw new IllegalArgumentException("extraCheckouts refFile 은 저장소 안 상대 경로여야 한다: ${refFile}") }
            if (!(dir ==~ DIR_RE)) { throw new IllegalArgumentException("extraCheckouts dir 은 워크스페이스 바로 아래 한 칸 이름이어야 한다: ${dir}") }
            if (!(cred ==~ CREDENTIALS_RE)) { throw new IllegalArgumentException("extraCheckouts credentialsId 가 비었거나 이상하다: ${cred}") }
            if (!dirs.add(dir)) { throw new IllegalArgumentException("extraCheckouts dir 이 겹친다: ${dir}") }
            out << [url: url, refFile: refFile, dir: dir, credentialsId: cred, optional: m.optional == true]
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

    /**
     * 항목 하나를 어떻게 할지: [sha: '<받을 sha>'] 또는 [skip: '<건너뛸 이유>'].
     * text 는 refFile 내용(파일이 없으면 null). optional 이 아니면 틀린 ref 는 IllegalArgumentException(지금과 같다).
     */
    static Map plan(Map e, String text) {
        if (!e.optional) { return [sha: parseRef(e.refFile, text)] }
        if (text == null) { return [skip: "${e.refFile} 없음".toString()] }
        String sha
        try {
            sha = parseRef(e.refFile, text)
        } catch (IllegalArgumentException ex) {
            return [skip: ex.message]
        }
        if (sha == ZERO_SHA) { return [skip: "${e.refFile} 가 0 들(고정한 커밋 없음)".toString()] }
        return [sha: sha]
    }

    /** optional 항목을 건너뛸 때 남기는 경고 한 줄. */
    static String skipWarning(Map e, String reason) {
        return "경고: 다른 저장소 받기 건너뜀(optional) — ${e.url} → ${e.dir}: ${reason}. 배포는 계속한다".toString()
    }

    /** 받기 명령. 인자는 모두 검증된 값이라 작은따옴표로 충분하다. */
    static String command(String script, String knownHosts, Map e, String sha) {
        if (!(sha ==~ SHA_RE)) { throw new IllegalArgumentException("sha 가 아니다: ${sha}") }
        return "SANDCORP_KNOWN_HOSTS='${knownHosts}' bash '${script}' '${e.dir}' '${e.url}' '${sha}'"
    }
}
