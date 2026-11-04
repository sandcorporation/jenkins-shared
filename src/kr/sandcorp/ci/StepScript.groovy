package kr.sandcorp.ci

/**
 * runLogged 가 단계 명령을 적어 두는 파일 경로와 그 방어.
 *
 * 단계 명령 파일은 `<tmp>/<prefix>-step-<key>.sh` — 라이브러리가 @tmp 에 쓰는 실제 스크립트
 * (sandcorp-deploy.sh·sandcorp-build-image.sh·migrate-guard.sh)와 이름이 겹치지 않게 `-step-` 을 넣는다.
 * 2026-11-04 사고(#19): 단계 명령 파일이 `sandcorp-deploy.sh` 라 `bash '<그 경로>'` 로 배포 스크립트를 덮어써
 * 자기 자신을 끝없이 부르다 oracle 이 멈췄다.
 *
 * 그래도 명령이 자기 파일 경로를 담으면(이름 규칙이 다시 겹치거나 저장소가 그 경로를 deployScript 로 주면)
 * 쓰기 전에 실패한다 — 덮어쓰면 자기 재귀다.
 */
class StepScript implements Serializable {
    static final String MARK = '-step-'

    /** 단계 명령 파일 경로. prefix 는 'sandcorp'(배포) 또는 'pr-check'(PR 검사). */
    static String path(String tmp, String prefix, String key) {
        if (!(key ==~ /[a-z]+/)) { throw new IllegalArgumentException("단계 key 는 영소문자만: ${key}") }
        return "${tmp}/${prefix}${MARK}${key}.sh"
    }

    /** 명령이 자기 파일 경로를 담으면 IllegalArgumentException. 경로 문자열을 그대로 찾는다(따옴표 안팎 상관없이). */
    static void guard(String cmdPath, String script) {
        if (script != null && script.contains(cmdPath)) {
            throw new IllegalArgumentException("단계 명령이 자기 파일(${cmdPath})을 실행한다 — 덮어쓰면 무한 재귀라 멈춘다")
        }
    }
}
