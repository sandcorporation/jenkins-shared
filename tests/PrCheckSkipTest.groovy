// PrCheckSkip(배포 전 재검사 생략 판정) 시험. Jenkins 없이 groovy 로 돈다: bash tests/run.sh
import kr.sandcorp.ci.PrCheckSkip

int n = 0
def M = 'm' * 40, P = 'p' * 40, H = 'h' * 40, T = 't' * 40, U = 'u' * 40

// ---- repoSlug
[
    'git@github.com:sandcorporation/portfolio.git'   : 'sandcorporation/portfolio',
    'https://github.com/sandcorporation/daypasge.git': 'sandcorporation/daypasge',
    'https://github.com/sandcorporation/AI-Trend-News': 'sandcorporation/AI-Trend-News',
    'git@gitlab.com:x/y.git'                         : null,
    ''                                               : null,
].each { url, want ->
    assert PrCheckSkip.repoSlug(url) == want: "repoSlug(${url}) = ${PrCheckSkip.repoSlug(url)}"
    n++
}

// ---- pickPr
def pull = { Map o = [:] -> [number: 176, merge_commit_sha: M, merged_at: '2026-11-02T06:12:00Z', base: [ref: 'main'], head: [sha: H]] + o }
def pick = { String what, List pulls, Integer wantNumber, String reasonHas = '' ->
    Map r = PrCheckSkip.pickPr(M, 'main', pulls)
    assert r.pr?.number == wantNumber: "${what}: ${r}"
    if (reasonHas) { assert r.reason.contains(reasonHas): "${what}: reason='${r.reason}'" }
    n++
}
pick('병합 PR 하나', [pull()], 176)
pick('PR 없음(직접 push)', [], null, '없다')
pick('열린 PR 이 이 커밋을 담고 있을 뿐', [pull(merged_at: null, merge_commit_sha: 'x' * 40)], null, '없다')
pick('다른 커밋으로 병합된 PR', [pull(merge_commit_sha: 'x' * 40)], null, '없다')
pick('다른 브랜치로 병합된 PR', [pull(base: [ref: 'release'])], null, '없다')
pick('같은 커밋을 낸 PR 이 둘(모호)', [pull(), pull(number: 177)], null, '2개')
pick('head sha 없음', [pull(head: [:])], null, 'head sha')

// ---- decide
def ok = [context: PrCheckSkip.CONTEXT, state: 'success']
def base = [pr: [number: 176, headSha: H], parentSha: P, mainTree: T, headTree: T,
            statuses: [[context: 'other', state: 'failure'], ok], compare: [behind_by: 0, ahead_by: 1]]
def decide = { String what, Map over, boolean want, String reasonHas ->
    Map r = PrCheckSkip.decide(base + over)
    assert r.skip == want: "${what}: skip=${r.skip} (${r.reason})"
    assert r.reason.contains(reasonHas): "${what}: reason='${r.reason}' 에 '${reasonHas}' 없음"
    n++
}
decide('PR 검사 통과 + head 가 부모 위 + tree 같음 → 생략', [:], true, 'PR #176 검사 통과(tree tttttttttttt 같음)로 생략')
decide('PR 모름', [pr: null], false, '재검사')
decide('PR 검사 결과 없음', [statuses: [[context: 'other', state: 'success']]], false, '결과가 없다')
decide('PR 검사 실패', [statuses: [ok + [state: 'failure']]], false, 'failure')
decide('PR 검사 아직 도는 중', [statuses: [ok + [state: 'pending']]], false, 'pending')
decide('head 가 병합 직전 main 뒤(다른 커밋이 끼었다)', [compare: [behind_by: 2, ahead_by: 1]], false, '위에 있지 않다')
decide('compare 응답 모름', [compare: null], false, '관계를 모른다')
decide('부모 모름', [parentSha: null], false, '부모를 모른다')
decide('tree 다름', [headTree: U], false, 'tree 가 다르다')
decide('tree 모름', [mainTree: null], false, 'tree 를 모른다')

println "PrCheckSkip 시험: 통과 ${n}"
