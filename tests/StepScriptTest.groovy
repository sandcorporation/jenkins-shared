// StepScript(runLogged 단계 명령 파일 경로) 시험. Jenkins 없이 groovy 로 돈다: bash tests/run.sh
import kr.sandcorp.ci.StepScript

int n = 0
def eq = { what, got, want -> assert got == want: "${what}: ${got} != ${want}"; n++ }
def refuses = { what, Closure c -> try { c(); assert false: "${what}: 통과했다" } catch (IllegalArgumentException ignored) { n++ } }

String tmp = '/w/sandcorp-portfolio@tmp'
// 라이브러리가 @tmp 에 쓰는 실제 스크립트(vars/*.groovy 의 writeFile). 단계 명령 파일은 어느 것과도 겹치면 안 된다.
List real = ["${tmp}/sandcorp-deploy.sh", "${tmp}/sandcorp-build-image.sh", "${tmp}/migrate-guard.sh", "${tmp}/migrate-guard.py"]*.toString()
List steps = ['check', 'image', 'deploy', 'step'].collect { StepScript.path(tmp, 'sandcorp', it) } +
    ['check', 'migrate'].collect { StepScript.path(tmp, 'pr-check', it) }

eq('배포 단계 파일', StepScript.path(tmp, 'sandcorp', 'deploy'), "${tmp}/sandcorp-step-deploy.sh".toString())
eq('PR 검사 단계 파일', StepScript.path(tmp, 'pr-check', 'migrate'), "${tmp}/pr-check-step-migrate.sh".toString())
eq('실제 스크립트와 겹침 없음', steps.intersect(real), [])
eq('단계끼리 겹침 없음', steps.unique(false).size(), steps.size())
refuses('key 에 경로 문자') { StepScript.path(tmp, 'sandcorp', '../deploy') }

// 사고 재현: 배포 단계 명령 `bash '<배포 스크립트>'` — 이제 경로가 달라 통과한다.
String deployScript = "${tmp}/sandcorp-deploy.sh"
String cmd = StepScript.path(tmp, 'sandcorp', 'deploy')
StepScript.guard(cmd, "bash '${deployScript}'"); n++
// 옛 이름(같은 경로)이거나 저장소가 그 경로를 deployScript 로 주면 쓰기 전에 멈춘다.
refuses('옛 충돌') { StepScript.guard(deployScript, "bash '${deployScript}'") }
refuses('deployScript = 단계 파일') { StepScript.guard(cmd, "bash '${cmd}'") }
StepScript.guard(cmd, null); n++

println "StepScriptTest 통과: ${n}건"
