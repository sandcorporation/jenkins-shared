package kr.sandcorp.ci

import java.util.regex.Pattern

/**
 * PR 검사가 실패했을 때 PR 에 다는 댓글 본문(sandcorpPrCheck). 작업자는 Jenkins 콘솔을 못 읽으므로 이 댓글을
 * wait-ci.py 가 `gh pr view --json comments` 로 읽는다 — 제목 줄(TITLE_PREFIX · head 12자)로 찾으니 모양을 바꾸면 거기도 고친다.
 * Jenkins·GitHub 를 부르지 않는 순수 함수라 tests/FailureCommentTest.groovy 가 바로 돌린다.
 *
 * 댓글은 저장소를 읽는 누구나 본다. 그래서 비밀값처럼 보이는 줄과 개인 키 블록은 뽑기 전에 지운다.
 */
class FailureComment implements Serializable {

    static final String TITLE_PREFIX = 'PR 검사 실패 — '
    static final int MAX_LINES = 150
    static final int MAX_BYTES = 60 * 1024
    static final int TAIL_LINES = 80
    static final int CONTEXT = 3
    static final int MAX_LINE_CHARS = 2000
    /** 단계 출력 파일에서 읽어 올 끝 줄 수(이 안에서 뽑는다). */
    static final int READ_LINES = 2000

    static final Pattern HIT = Pattern.compile(/FAILED|Error|error:|Traceback|AssertionError|^E   |budget|예산/)
    static final Pattern SECRET = Pattern.compile(/ghp_|github_pat_|-----BEGIN|(?i:password=)|(?i:token=)|https:\/\/discord/)
    static final Pattern KEY_BEGIN = Pattern.compile(/-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----/)
    static final Pattern KEY_END = Pattern.compile(/-----END [A-Z0-9 ]*PRIVATE KEY-----/)

    /** 비밀값 줄을 지운다. 개인 키 블록(BEGIN…END)은 통째로, END 가 없으면 끝까지. */
    static List<String> redact(List<String> lines) {
        List<String> out = []
        boolean inKey = false
        for (String l : lines) {
            if (inKey) {
                if (KEY_END.matcher(l).find()) { inKey = false }
                continue
            }
            if (KEY_BEGIN.matcher(l).find()) { inKey = !KEY_END.matcher(l).find(); continue }
            // sh -x 추적 줄(+ echo -----BEGIN …)이 블록을 먼저 열고 닫으면 실제 출력의 END 줄이 짝 없이 남는다.
            if (KEY_END.matcher(l).find()) { continue }
            if (SECRET.matcher(l).find()) { continue }
            out << l
        }
        return out
    }

    /** 출력(끝부분)에서 실패 요지를 뽑는다: 실패 줄과 앞뒤 CONTEXT 줄, 끝 쪽부터 MAX_LINES·MAX_BYTES 안. 없으면 끝 TAIL_LINES 줄. */
    static String excerpt(String text) {
        List<String> lines = []
        for (String l : (text ?: '').split('\n', -1)) {
            String s = l.endsWith('\r') ? l.substring(0, l.length() - 1) : l
            lines << (s.length() > MAX_LINE_CHARS ? s.substring(0, MAX_LINE_CHARS) + ' …(줄 잘림)' : s)
        }
        while (lines && lines[-1].trim().isEmpty()) { lines.remove(lines.size() - 1) }
        lines = redact(lines)

        // 끝에서부터 실패 줄 묶음([시작, 끝])을 모은다. 겹치거나 맞닿으면 합친다.
        List<List<Integer>> ranges = []
        int budget = MAX_LINES
        for (int i = lines.size() - 1; i >= 0 && budget > 0; i--) {
            if (!HIT.matcher(lines[i]).find()) { continue }
            int a = Math.max(0, i - CONTEXT)
            int b = Math.min(lines.size() - 1, i + CONTEXT)
            if (ranges && b >= ranges[0][0] - 1) {
                int oldA = ranges[0][0]
                ranges[0][0] = Math.min(a, oldA)
                budget -= (oldA - ranges[0][0])
            } else {
                ranges.add(0, [a, b])
                budget -= (b - a + 1) + 1   // + 줄임표
            }
        }

        List<String> picked = []
        if (ranges) {
            for (int r = 0; r < ranges.size(); r++) {
                if (r > 0 || ranges[r][0] > 0) { picked << '…' }
                picked.addAll(lines.subList(ranges[r][0], ranges[r][1] + 1))
            }
            if (ranges[-1][1] < lines.size() - 1) { picked << '…' }
        } else {
            picked.addAll(lines.subList(Math.max(0, lines.size() - TAIL_LINES), lines.size()))
        }

        // 넘치면 앞(오래된 쪽)부터 버린다.
        while (picked.size() > MAX_LINES) { picked.remove(0) }
        int bytes = 0
        for (String l : picked) { bytes += l.getBytes('UTF-8').length + 1 }
        while (picked && bytes > MAX_BYTES) { bytes -= picked.remove(0).getBytes('UTF-8').length + 1 }
        return picked.join('\n')
    }

    /**
     * @param a.stage 단계 이름 · a.head PR head sha · a.where 노드(home|macos) · a.build 빌드 번호 · a.url 빌드 URL
     *        a.code 종료 코드 · a.output 뽑을 출력(끝부분)
     */
    static String body(Map a) {
        String ex = excerpt(a.output as String)
        int longest = 0
        def m = ex =~ /`+/
        while (m.find()) { longest = Math.max(longest, m.group().length()) }
        String fence = '`' * Math.max(3, longest + 1)
        String head = (a.head ?: '?') as String
        return [
            "${TITLE_PREFIX}${a.stage} · ${head.take(12)} · ${a.where} · 빌드 #${a.build}",
            '',
            fence,
            ex,
            fence,
            "종료 코드 ${a.code} · ${a.url ?: ''}".trim(),
        ].join('\n')
    }

    /** env.CHANGE_URL(https://github.com/owner/repo/pull/N) → owner/repo, 아니면 null */
    static String repoFromChangeUrl(String url) {
        def m = (url ?: '') =~ /^https:\/\/github\.com\/([^\/]+)\/([^\/]+)\/pull\/\d+/
        return m.find() ? "${m.group(1)}/${m.group(2)}".toString() : null
    }

    /** `git rev-list --parents -n 1 HEAD` 출력 → PR head. 머지 결과면 두 번째 부모, 빨리감기였으면 HEAD 자신. */
    static String prHead(String parents) {
        List<String> p = (parents ?: '').trim().split(/\s+/) as List
        return p.size() >= 3 ? p[2] : p[0]
    }
}
