package kr.sandcorp.ci

/**
 * 저장소 빌드 스크립트(`buildScript`)가 배포 스크립트(`deployScript`)에 넘기는 값.
 *
 * 빌드 노드(macos·home)와 배포 노드(oracle)는 셸 환경을 나누지 않는다. 그래서 빌드 스크립트는
 * `$SANDCORP_DEPLOY_ENV` 파일에 `KEY=VALUE` 줄을 쓰고, 라이브러리가 그 파일을 읽어 배포 스크립트의 환경으로 넘긴다
 * (codemit: python3 가 있는 빌드 노드가 낸 문맥 해시 — oracle 에는 python3 가 없다).
 *
 * 받는 것은 `KEY=VALUE` 줄뿐이다 — 키는 영대문자로 시작하는 영대문자·숫자·밑줄, 값은 한 줄. 라이브러리가
 * 배포 스크립트에 넘기는 이름(`SANDCORP_`·`DEPLOY_`·`COMPOSE_` 로 시작)과 `PATH`·`HOME` 은 덮지 못한다.
 * 빈 줄·`#` 주석은 건너뛰고, 그 밖의 줄은 IllegalArgumentException(조용히 버리면 배포가 다른 값으로 돈다).
 */
class BuildOutput implements Serializable {
    static final int MAX_LINES = 50
    static final int MAX_VALUE_CHARS = 4000
    static final List<String> RESERVED_PREFIXES = ['SANDCORP_', 'DEPLOY_', 'COMPOSE_'].asImmutable()
    static final List<String> RESERVED_NAMES = ['PATH', 'HOME'].asImmutable()

    /** 파일 내용 → withEnv 에 그대로 넣을 `KEY=VALUE` 목록(같은 키는 뒤엣것). */
    static List<String> parse(String text) {
        Map<String, String> out = new LinkedHashMap<String, String>()
        if (text == null) { return [] }
        for (String raw : text.split('\n')) {
            String line = raw.endsWith('\r') ? raw.substring(0, raw.length() - 1) : raw
            if (line.trim().isEmpty() || line.trim().startsWith('#')) { continue }
            int eq = line.indexOf('=')
            if (eq <= 0) { throw new IllegalArgumentException("KEY=VALUE 줄이 아니다: ${line.take(80)}") }
            String key = line.substring(0, eq)
            String value = line.substring(eq + 1)
            if (!(key ==~ /[A-Z][A-Z0-9_]*/)) { throw new IllegalArgumentException("키는 영대문자·숫자·밑줄: ${key.take(80)}") }
            if (RESERVED_NAMES.contains(key) || RESERVED_PREFIXES.any { key.startsWith(it) }) {
                throw new IllegalArgumentException("라이브러리 몫의 이름은 덮지 못한다: ${key}")
            }
            if (value.length() > MAX_VALUE_CHARS) { throw new IllegalArgumentException("값이 ${MAX_VALUE_CHARS}자를 넘는다: ${key}") }
            out.remove(key)
            out.put(key, value)
            if (out.size() > MAX_LINES) { throw new IllegalArgumentException("${MAX_LINES}줄을 넘는다") }
        }
        return out.collect { k, v -> "${k}=${v}".toString() }
    }
}
