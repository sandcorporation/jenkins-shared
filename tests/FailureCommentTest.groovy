// FailureComment(PR 검사 실패 댓글 본문) 시험. Jenkins 없이 groovy 로 돈다: bash tests/run.sh
import kr.sandcorp.ci.FailureComment

int n = 0
def ok = { what, cond -> assert cond: what; n++ }
def eq = { what, got, want -> assert got == want: "${what}: ${got} != ${want}"; n++ }

// 실패 줄 앞뒤 몇 줄만 뽑는다. 떨어진 묶음 사이는 「…」.
List<String> noise = (1..200).collect { "ok line ${it}" }
String log = (noise[0..99] + ['tests/test_a.py::test_x FAILED', 'E   assert 1 == 2'] + noise[100..199] + ['Traceback (most recent call last):', '  File "x.py"', 'ValueError: boom']).join('\n')
String ex = FailureComment.excerpt(log)
List<String> exl = ex.split('\n') as List
ok('FAILED 줄이 있다', exl.contains('tests/test_a.py::test_x FAILED'))
ok('E 줄이 있다', exl.contains('E   assert 1 == 2'))
ok('Traceback 이 있다', exl.contains('Traceback (most recent call last):'))
ok('앞 문맥 3줄', exl.contains('ok line 98') && !exl.contains('ok line 96'))
ok('묶음 사이 줄임표', exl.contains('…'))
ok('관계없는 가운데는 없다', !exl.contains('ok line 150'))
ok('budget 줄', FailureComment.excerpt('a\nCPU budget exceeded: 3.2s > 2s\nb').contains('budget'))
ok('error: 줄', FailureComment.excerpt('x\nsrc/a.ts(3,1): error: TS2322\ny').contains('TS2322'))

// 뽑을 게 없으면 끝 80줄.
List<String> plain = FailureComment.excerpt(noise.join('\n')).split('\n') as List
eq('끝 80줄', plain.size(), 80)
eq('끝 80줄의 끝', plain[-1], 'ok line 200')

// 최대 150줄, 끝 쪽 실패를 먼저 남긴다.
String many = (1..400).collect { "case ${it} FAILED" }.join('\n')
List<String> ml = FailureComment.excerpt(many).split('\n') as List
ok("150줄 이하(${ml.size()})", ml.size() <= 150)
eq('끝 실패가 남는다', ml[-1], 'case 400 FAILED')

// 60KB 이하(UTF-8).
String fat = (1..150).collect { "FAILED " + ('가' * 900) }.join('\n')
ok('60KB 이하', FailureComment.excerpt(fat).getBytes('UTF-8').length <= FailureComment.MAX_BYTES)

// 비밀값 줄은 지운다. 개인 키 블록은 통째로.
String sec = ['Error: one', 'token=abc', 'url https://discord.com/api/webhooks/1/x', 'ghp_AAAA', 'github_pat_BBBB', 'PASSWORD=x password=y',
              '-----BEGIN OPENSSH PRIVATE KEY-----', 'b3BlbnNzaC1rZXk', '-----END OPENSSH PRIVATE KEY-----', 'Error: two'].join('\n')
String red = FailureComment.excerpt(sec)
['token=', 'discord', 'ghp_', 'github_pat_', 'password=', 'BEGIN', 'b3BlbnNzaC1rZXk', 'END OPENSSH'].each { s -> ok("지움: ${s}", !red.contains(s)) }
ok('남길 줄은 남는다', red.contains('Error: one') && red.contains('Error: two'))
// 끝나지 않은 키 블록 뒤는 전부 지운다.
ok('닫히지 않은 키 블록', !FailureComment.excerpt('Error: a\n-----BEGIN RSA PRIVATE KEY-----\nMIIE\nError: b').contains('MIIE'))

// sh -x 추적이 섞이면(+ echo -----BEGIN … 다음에 실제 출력) 짝 없는 END 줄도 남기지 않는다(PR 검사 실제 잡에서 본 모양).
String traced = ['+ echo -----BEGIN OPENSSH PRIVATE KEY-----', '-----BEGIN OPENSSH PRIVATE KEY-----', '+ echo S0VZ', 'S0VZ',
                 '+ echo -----END OPENSSH PRIVATE KEY-----', '-----END OPENSSH PRIVATE KEY-----', 'Error: after'].join('\n')
String tr = FailureComment.excerpt(traced)
ok('추적 섞인 키 블록', !tr.contains('PRIVATE KEY') && !tr.contains('S0VZ') && tr.contains('Error: after'))

// 본문: 제목 줄 + 코드 블록. 출력에 ``` 가 있으면 더 긴 울타리.
String body = FailureComment.body(stage: '검사', head: '0123456789abcdef0123', where: 'home', build: '42', url: 'https://j/job/x/42/', code: 1, output: 'Error: x\n```\ny')
List<String> bl = body.split('\n') as List
eq('제목 줄', bl[0], 'PR 검사 실패 — 검사 · 0123456789ab · home · 빌드 #42')
ok('제목 접두사로 찾는다', bl[0].startsWith(FailureComment.TITLE_PREFIX))
ok('울타리가 출력의 ``` 보다 길다', body.contains('````\nError: x'))
ok('종료 코드·링크', body.contains('종료 코드 1') && body.contains('https://j/job/x/42/'))

// PR head: 머지 커밋이면 두 번째 부모, 빨리감기면 HEAD.
eq('머지 커밋', FailureComment.prHead('m p1 p2'), 'p2')
eq('빨리감기', FailureComment.prHead('h p1'), 'h')

eq('CHANGE_URL', FailureComment.repoFromChangeUrl('https://github.com/sandcorporation/codemit/pull/7'), 'sandcorporation/codemit')
eq('CHANGE_URL 없음', FailureComment.repoFromChangeUrl(null), null)

println "FailureCommentTest 통과: ${n}건"
