// BuildOutput(buildScript → deployScript 로 넘기는 KEY=VALUE) 시험. Jenkins 없이 groovy 로 돈다: bash tests/run.sh
import kr.sandcorp.ci.BuildOutput

int n = 0
def eq = { what, got, want -> assert got == want: "${what}: ${got} != ${want}"; n++ }
def refuses = { what, Closure c -> try { c(); assert false: "${what}: 통과했다" } catch (IllegalArgumentException ignored) { n++ } }

eq('빈 값', BuildOutput.parse(''), [])
eq('null', BuildOutput.parse(null), [])
eq('codemit 문맥 해시',
    BuildOutput.parse('CODEMIT_CTX_BASE=aaa\nCODEMIT_CTX_HEAD=bbb\nCODEMIT_CTX_CHANGED=backend frontend\n'),
    ['CODEMIT_CTX_BASE=aaa', 'CODEMIT_CTX_HEAD=bbb', 'CODEMIT_CTX_CHANGED=backend frontend'])
eq('빈 값도 값이다', BuildOutput.parse('CODEMIT_CTX_CHANGED='), ['CODEMIT_CTX_CHANGED='])
eq('값의 = 는 그대로', BuildOutput.parse('A=b=c'), ['A=b=c'])
eq('주석·빈 줄·CRLF', BuildOutput.parse('# 주석\n\nA=1\r\n'), ['A=1'])
eq('같은 키는 뒤엣것', BuildOutput.parse('A=1\nB=2\nA=3'), ['B=2', 'A=3'])

refuses('= 없음') { BuildOutput.parse('그냥 줄') }
refuses('키 소문자') { BuildOutput.parse('a=1') }
refuses('키 공백') { BuildOutput.parse('A B=1') }
refuses('라이브러리 몫 SANDCORP_') { BuildOutput.parse('SANDCORP_ROLLBACK=1') }
refuses('라이브러리 몫 DEPLOY_') { BuildOutput.parse('DEPLOY_IMAGES=x') }
refuses('라이브러리 몫 COMPOSE_') { BuildOutput.parse('COMPOSE_FILE=x') }
refuses('PATH') { BuildOutput.parse('PATH=/tmp') }
refuses('긴 값') { BuildOutput.parse('A=' + 'x' * (BuildOutput.MAX_VALUE_CHARS + 1)) }
refuses('줄 수') { BuildOutput.parse((1..BuildOutput.MAX_LINES + 1).collect { "K${it}=v" }.join('\n')) }

println "BuildOutputTest 통과: ${n}건"
