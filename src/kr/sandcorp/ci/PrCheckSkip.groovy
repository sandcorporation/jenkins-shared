package kr.sandcorp.ci

/**
 * 배포 실패 댓글을 달 대상 PR 을 찾기 위한 GitHub 응답 파싱 헬퍼.
 * Jenkins·GitHub 를 부르지 않는 순수 함수라 tests/PrCheckSkipTest.groovy 가 바로 돌린다.
 *
 * 왜 이 조건이면 같은 트리인가:
 *   PR 검사는 PR head(H)를 그때의 main(B)에 머지한 결과를 검사한다. main 은 앞으로만 가므로 B 는 병합 커밋 M 의 부모 P 의 조상이다.
 *   P 가 H 의 조상이면 B 도 H 의 조상이라 머지 결과는 H 그대로다 → 검사한 트리 = tree(H).
 *   여기에 tree(M) == tree(H) 를 직접 확인한다(squash·merge 커밋 어느 쪽이든).
 * 하나라도 모르거나 어긋나면 재검사한다(애매하면 재검사).
 */
class PrCheckSkip implements Serializable {

    static final String CONTEXT = 'continuous-integration/jenkins/pr-merge'

    /** git@github.com:owner/repo.git · https://github.com/owner/repo(.git) → owner/repo, 아니면 null */
    static String repoSlug(String url) {
        def m = (url ?: '') =~ /github\.com[:\/]([^\/]+)\/([^\/]+?)(?:\.git)?\/?$/
        return m.find() ? "${m.group(1)}/${m.group(2)}".toString() : null
    }

    /**
     * GET commits/{M}/pulls 의 응답에서 M 을 만든 PR 하나를 고른다.
     * @return [pr: [number, headSha], reason: null] 또는 [pr: null, reason: '…']
     */
    static Map pickPr(String mainSha, String deployBranch, List pulls) {
        List hits = (pulls ?: []).findAll { Map p ->
            p.merge_commit_sha == mainSha && p.merged_at && (p.base?.ref ?: '') == (deployBranch ?: 'main')
        }
        if (hits.size() != 1) {
            return [pr: null, reason: (hits ? "M 을 만든 PR 이 ${hits.size()}개" : '이 커밋을 만든 병합 PR 이 없다(직접 push 등)').toString()]
        }
        Map p = hits[0]
        if (!p.head?.sha) { return [pr: null, reason: "PR #${p.number} head sha 를 모른다".toString()] }
        return [pr: [number: p.number, headSha: p.head.sha], reason: null]
    }

    /**
     * @param a.mainTree    tree(M)
     * @param a.parentSha   M 의 첫 부모 P
     * @param a.pr          pickPr 의 pr ([number, headSha])
     * @param a.statuses    GET commits/{H}/status 의 statuses(문맥마다 최신 하나)
     * @param a.compare     GET compare/{P}...{H} 응답(behind_by 를 본다)
     * @param a.headTree    tree(H)
     * @return [skip: boolean, reason: String]
     */
    static Map decide(Map a) {
        Map pr = a.pr
        if (!pr) { return recheck('PR 을 모른다') }
        String n = "PR #${pr.number}"
        Map st = (a.statuses ?: []).find { it.context == CONTEXT }
        if (!st) { return recheck("${n} 에 PR 검사(${CONTEXT}) 결과가 없다") }
        if (st.state != 'success') { return recheck("${n} PR 검사가 ${st.state}") }
        if (!a.parentSha) { return recheck('M 의 부모를 모른다') }
        if (a.compare?.behind_by == null) { return recheck("${n} head 와 main 의 관계를 모른다") }
        if (a.compare.behind_by != 0) {
            return recheck("${n} head 가 병합 직전 main(${DeployGate.short12(a.parentSha)}) 위에 있지 않다 — PR 검사가 본 트리와 다를 수 있다")
        }
        if (!a.mainTree || !a.headTree) { return recheck('tree 를 모른다') }
        if (a.mainTree != a.headTree) {
            return recheck("tree 가 다르다: main ${DeployGate.short12(a.mainTree)} · ${n} head ${DeployGate.short12(a.headTree)}")
        }
        return [skip: true, reason: "${n} 검사 통과(tree ${DeployGate.short12(a.mainTree)} 같음)로 생략".toString()]
    }

    static Map recheck(String why) {
        return [skip: false, reason: "재검사: ${why}".toString()]
    }
}
