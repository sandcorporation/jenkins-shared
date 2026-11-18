# jenkins-shared

Sandcorp Jenkins shared library for deployment only. GitHub Actions is the sole executor for builds, tests, PR validation, and image builds. Jenkins must never build or test on its controller, macOS node, or another Jenkins node. Jenkins may deploy only GHCR images built and published by Actions.

## Deployment contract

Repositories call `sandcorpDeploy` from their deployment `Jenkinsfile`. Images must be tagged by the source commit SHA and pushed to GHCR by Actions before Jenkins deploys. Jenkins checks the registry for each requested image, then runs the deployment on `oracle` (or the node label given as `deployNode`); the `built-in` node handles deployment gate and reporting only and cannot be a `deployNode`.

```groovy
@Library('sandcorp') _

sandcorpDeploy(
  name: 'Portfolio',
  image: 'ghcr.io/sandcorporation/portfolio',
  imageVar: 'PORTFOLIO_IMAGE',
  composeProject: 'portfolio',
  composeFiles: ['docker-compose.prod.yml', 'docker-compose.oracle.yml'],
  dbCheck: 'python manage.py check --database default',
  migrate: ['python manage.py migrate --noinput'],
  healthCmd: 'python -c "..."',
)
```

`image` + `imageVar` describes one GHCR image. For multiple images use `images: [[repo: 'ghcr.io/org/web', envVar: 'WEB_IMAGE'], ...]`. Compose files receive the image variable set to `<repo>:<commit-sha>`. The optional `deployFrom` selects a promotion branch; all deploys, including rollback by `ROLLBACK_SHA`, wait for the exact GHCR image to appear. Jenkins never builds an image.

Deployment options include `name`, `composeProject`, `image`/`imageVar` or `images`, `deployFrom`/`branch`, `imageWaitMinutes`, `imageWaitHint`, `composeFiles`, `deployNode`, `deployDir`, `service`, `preServices`, `dbCheck`, `migrate`, `keepServices`, `extraServices`, `healthCmd`, `healthUrl`, `keepImages`, `requiredEnv`, `autoRollback`, `extraCheckouts`, and `deployScript`. Deployment, rollback, GitHub status reporting, and Discord notifications remain Jenkins responsibilities.

`deployNode` (optional) is a plain Jenkins node label (letters, digits, `_`, `.`, `-`; no label expressions) for a repository whose service runs on a host other than `oracle`. Omitted or empty means `oracle`, so existing Jenkinsfiles are unchanged. The node needs Docker with Compose, read access to the GHCR images, and the job workspace `.env` as on `oracle`.

`extraCheckouts` (optional) fetches other repositories at a pinned commit during the deploy stage, before anything is replaced: `[[url: 'git@github.com:<owner>/<repo>.git', refFile: 'path/in/repo.ref', dir: '<one-level dir>', credentialsId: '<ssh key credential>'], ...]`. `refFile` holds one 40-character lowercase sha. By default a missing or malformed ref, a missing credential, or a failed fetch stops the deploy. Add `optional: true` to an entry to turn those cases, and an all-zero sha (no commit pinned yet), into a `경고: 다른 저장소 받기 건너뜀(optional) …` line; the entry is skipped and the deploy continues.

`preServices` and `keepServices` are base services (database, cache, fixed-tag services). Before bringing one up, the deploy script skips `up` when the running container was created from a different image string that resolves to the same local image ID and the service definition is otherwise unchanged (for example `postgres:17-alpine` to `postgres:17.11-alpine@sha256:...` of the same image), and prints `<svc> 유지 — image 문자열만 바뀌고 같은 이미지(재생성 안 함)`. Compose hashes the image string into the service definition, so without this check pinning a tag to its digest recreates the database container. Any other change, or a digest that is not yet local, still goes through `up`.

`requiredEnv` (optional) lists keys that must have a non-empty value in the deployment `.env`; if any is missing the deploy stops before pulling or replacing anything (values are never printed). Use it for settings whose Compose default would silently change behaviour, such as a bind address. The workspace `.env` is lost whenever Jenkins wipes the workspace (for example after a failed checkout). To keep it outside, set the node environment variable `DEPLOY_ENV_FILE` to an env file path on that node; the deploy script then recreates `./.env` as a link to it before every deploy (the node file wins over an existing workspace `.env`). Keep the path in the node configuration, not in a repository.

The retired arguments `check`, `buildSsh`, `builder`, `buildScript`, `buildCredentials`, `platform`, `sourceUrl`, `skipCheckedTree`, and `macWaitSeconds` fail fast with migration guidance. Move those checks and image-building steps to the repository's GitHub Actions workflows. Remove `Jenkinsfile.pr` jobs and any calls to `sandcorpPrCheck`; PR required checks must come from Actions. The shared library no longer exposes Jenkins build-node selection.

## Build and test this library

Run `bash tests/run.sh` (Docker required). It exercises deployment shell behavior (including a real Docker Compose check that a tag-to-digest change keeps the base service container), GHCR image waiting, helper pure functions, Groovy compilation, shellcheck, and migration guard utilities. Keep consumer Actions workflows authoritative for CI; this test runner is a maintainer validation tool, not a Jenkins job.
