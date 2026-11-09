// 다른 곳(GitHub Actions 등)에서 굽는 이미지들이 레지스트리에 모두 나타날 때까지 기다린다.
// 이미지를 Jenkins가 굽지 않는 저장소가 배포 단계 앞에서 부른다. sandcorpDeploy는 deployFrom 을 줄 때만 부른다.
//
//   waitForGhcrImage(images: ["ghcr.io/<org>/web:${sha}", "ghcr.io/<org>/api:${sha}"])
//   waitForGhcrImage(image: '...', timeoutMinutes: 30, intervalSeconds: 10, hint: 'https://github.com/<org>/<repo>/actions')
//
// 이 노드의 docker 로그인(배포 노드의 pull 자격)으로 `docker manifest inspect`를 되풀이한다. 새 자격을 쓰지 않는다.
// 상한(기본 30분)을 넘으면 error로 끝난다 — 이미지 워크플로가 실패해도 무한히 기다리지 않는다.
def call(Map args = [:]) {
    List<String> images = []
    if (args.images) {
        images.addAll(args.images.collect { it.toString().trim() })
    }
    if (args.image) {
        images.add(args.image.toString().trim())
    }
    images = images.findAll { it }.unique()
    if (!images) {
        error('waitForGhcrImage: images(또는 image)가 비었다')
    }
    if (images.any { it =~ /\s/ }) {
        error("waitForGhcrImage: 이미지 이름에 공백이 있다: ${images}")
    }
    int timeoutMinutes = (args.timeoutMinutes ?: 30) as int
    int intervalSeconds = (args.intervalSeconds ?: 10) as int
    String hint = args.hint?.toString() ?: ''
    String script = "${pwd(tmp: true)}/sandcorp-wait-ghcr-image.sh"
    writeFile file: script, text: libraryResource('sandcorp/wait-ghcr-image.sh')
    withEnv(["IMAGES=${images.join(' ')}", "WAIT_TIMEOUT_SECONDS=${timeoutMinutes * 60}",
             "WAIT_INTERVAL_SECONDS=${intervalSeconds}", "WAIT_HINT=${hint}"]) {
        int rc = sh(script: "bash '${script}'", returnStatus: true)
        if (rc != 0) {
            error("이미지가 ${timeoutMinutes}분 안에 모두 나타나지 않았다: ${images.join(', ')} — 이미지 워크플로(GitHub Actions) 결과를 보라")
        }
    }
}
