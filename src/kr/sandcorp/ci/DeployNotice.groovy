package kr.sandcorp.ci

import groovy.json.JsonOutput

/**
 * 배포 끝 알림을 보낼 곳과 본문. Jenkins API 를 부르지 않는다 — 보내기는 vars/sandcorpDeploy.groovy 의 notifyDiscord.
 *
 * 곳마다 따로 보낸다(한쪽 실패가 다른 쪽을 막지 않는다). optional 곳은 Jenkins 에 자격이 없으면 한 줄만 남기고 건너뛴다.
 */
class DeployNotice implements Serializable {

    /** 차례대로 보낸다. 본문은 모두 같다(디스코드 꼴 {"content": …}). */
    static final List<Map> TARGETS = [
        [label: '디스코드', credentialsId: Nodes.DISCORD_CREDENTIALS, optional: false].asImmutable(),
        [label: '둘째 웹훅', credentialsId: Nodes.NOTICE_CREDENTIALS, optional: true].asImmutable(),
    ].asImmutable()

    static String payload(String text) {
        return JsonOutput.toJson([content: text])
    }

    /** optional 곳의 자격이 없을 때 콘솔에 남기는 한 줄. 웹훅 주소는 담지 않는다. */
    static String skipLine(Map target, String why) {
        return "알림 건너뜀 — ${target.label}: 자격 ${target.credentialsId} 를 쓸 수 없음(${why}). 배포 결과는 그대로".toString()
    }
}
