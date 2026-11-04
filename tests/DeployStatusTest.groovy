// DeployStatus(배포 커밋 상태 sandcorp/deploy) 시험. Jenkins 없이 groovy 로 돈다: bash tests/run.sh
import kr.sandcorp.ci.DeployGate
import kr.sandcorp.ci.DeployStatus
import kr.sandcorp.ci.FailureComment

int n = 0
def ok = { what, cond -> assert cond: what; n++ }
def eq = { what, got, want -> assert got == want: "${what}: ${got} != ${want}"; n++ }

// 시작은 pending, target_url 없음.
Map p = DeployStatus.pending(42)
eq('pending 상태', p.state, 'pending')
eq('pending 설명', p.description, '빌드 #42 · 배포 중')
eq('context', p.context, 'sandcorp/deploy')
ok('target_url 없음', !p.containsKey('target_url'))

// 성공: 노드·검사 생략·시간.
Map s = DeployStatus.finish(build: 7, result: 'SUCCESS', builtOn: 'macos', checkSkipped: 'PR #3 검사 통과로 생략', took: '2분 3초')
eq('성공 상태', s.state, 'success')
eq('성공 설명', s.description, '빌드 #7 · 성공 · 검사 생략 · 빌드 macos · 2분 3초')
eq('되돌리기 아닌 성공(노드 없음)', DeployStatus.finish(build: 8, result: 'SUCCESS').description, '빌드 #8 · 성공')

// 실패 갈래.
Map f = DeployStatus.finish(build: 9, result: 'FAILURE', failedStage: '검사', builtOn: 'home')
eq('실패 상태', f.state, 'failure')
eq('실패 단계', f.description, '빌드 #9 · 검사 실패 · 빌드 home')
eq('단계 모름', DeployStatus.finish(build: 9, result: 'FAILURE').description, '빌드 #9 · 실패(FAILURE)')
eq('UNSTABLE 도 실패', DeployStatus.finish(build: 9, result: 'UNSTABLE').state, 'failure')
Map ab = DeployStatus.finish(build: 10, result: 'ABORTED')
eq('중단은 failure', ab.state, 'failure')
ok('중단 설명', ab.description.contains('중단'))
ok('자동 롤백', DeployStatus.finish(build: 11, result: 'FAILURE', rolledBackTo: '0123456789abcdef', failedStage: '배포').description
    .contains('실패 → 0123456789ab 자동 롤백'))
ok('롤백도 실패', DeployStatus.finish(build: 12, result: 'FAILURE', rollbackFailed: true, rolledBackTo: 'x').description.contains('자동 롤백도 실패'))
ok('결과 없음은 실패', DeployStatus.finish(build: 1).state == 'failure')

// 140자 안.
Map longOne = DeployStatus.finish(build: 1, result: 'SUCCESS', builtOn: 'macos', took: 'x' * 300)
ok("140자 이하(${longOne.description.length()})", longOne.description.length() <= DeployStatus.MAX_DESCRIPTION)
ok('잘리면 줄임표', longOne.description.endsWith('…'))
eq('딱 140자', DeployStatus.payload('success', 'a' * 140).description.length(), 140)

// 건너뜀: 끝난 상태는 덮지 않는다. 없거나 pending 이면 남긴다.
eq('이미 success', DeployStatus.onSkip(build: 2, kind: 'deployed', existing: 'success'), null)
eq('이미 failure', DeployStatus.onSkip(build: 2, kind: 'failed', existing: 'failure'), null)
eq('이미 error', DeployStatus.onSkip(build: 2, kind: 'deployed', existing: 'error'), null)
Map sk = DeployStatus.onSkip(build: 2, kind: 'deployed', existing: null)
eq('없으면 success', sk.state, 'success')
ok('건너뜀 설명', sk.description.startsWith('빌드 #2 · 건너뜀'))
eq('pending 이 남았으면 덮는다', DeployStatus.onSkip(build: 2, kind: 'deployed', existing: 'pending').state, 'success')
eq('실패 sha 건너뜀', DeployStatus.onSkip(build: 2, kind: 'failed', existing: null).state, 'failure')
eq('브랜치 아님은 안 남김', DeployStatus.onSkip(build: 2, kind: 'branch', existing: null), null)
eq('kind 없음', DeployStatus.onSkip(build: 2, existing: null), null)

// 상태 목록에서 이 context.
eq('context 찾기', DeployStatus.stateIn([[context: 'continuous-integration/jenkins/pr-merge', state: 'failure'], [context: 'sandcorp/deploy', state: 'success']]), 'success')
eq('없음', DeployStatus.stateIn([[context: 'x', state: 'success']]), null)
eq('null 목록', DeployStatus.stateIn(null), null)

// DeployGate 의 kind(건너뜀 갈래 → onSkip).
eq('kind deployed', DeployGate.decide(mainSha: 'a', branch: 'origin/main', deployBranch: 'main', lastSuccess: [main: 'a']).kind, 'deployed')
eq('kind failed', DeployGate.decide(mainSha: 'a', branch: 'origin/main', deployBranch: 'main', lastAttempt: [main: 'a', result: 'FAILURE']).kind, 'failed')
eq('kind branch', DeployGate.decide(mainSha: 'a', branch: 'origin/x', deployBranch: 'main').kind, 'branch')
eq('kind deploy', DeployGate.decide(mainSha: 'a', branch: 'origin/main', deployBranch: 'main').kind, 'deploy')

// 배포 실패 댓글: 제목 앞머리만 다르고 본문 규칙(비밀값 제거)은 PR 검사와 같다.
String body = FailureComment.body(prefix: DeployStatus.COMMENT_PREFIX, stage: '배포', head: '0123456789abcdef', where: 'oracle', build: '5',
    code: 1, output: 'Error: x\ntoken=abc')
List<String> bl = body.split('\n') as List
eq('배포 실패 제목', bl[0], '배포 실패 — 배포 · 0123456789ab · oracle · 빌드 #5')
ok('비밀값 지움', !body.contains('token='))
ok('빌드 URL 없으면 종료 코드만', bl[-1] == '종료 코드 1')
ok('PR 검사 제목은 그대로', FailureComment.body(stage: '검사', head: 'h', where: 'home', build: '1', code: 1, output: 'x').startsWith(FailureComment.TITLE_PREFIX))

println "DeployStatusTest 통과: ${n}건"
