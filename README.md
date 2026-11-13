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

Deployment options include `name`, `composeProject`, `image`/`imageVar` or `images`, `deployFrom`/`branch`, `imageWaitMinutes`, `imageWaitHint`, `composeFiles`, `deployNode`, `deployDir`, `service`, `preServices`, `dbCheck`, `migrate`, `keepServices`, `extraServices`, `healthCmd`, `healthUrl`, `keepImages`, `autoRollback`, `extraCheckouts`, and `deployScript`. Deployment, rollback, GitHub status reporting, and Discord notifications remain Jenkins responsibilities.

`deployNode` (optional) is a plain Jenkins node label (letters, digits, `_`, `.`, `-`; no label expressions) for a repository whose service runs on a host other than `oracle`. Omitted or empty means `oracle`, so existing Jenkinsfiles are unchanged. The node needs Docker with Compose, read access to the GHCR images, and the job workspace `.env` as on `oracle`.

The retired arguments `check`, `buildSsh`, `builder`, `buildScript`, `buildCredentials`, `platform`, `sourceUrl`, `skipCheckedTree`, and `macWaitSeconds` fail fast with migration guidance. Move those checks and image-building steps to the repository's GitHub Actions workflows. Remove `Jenkinsfile.pr` jobs and any calls to `sandcorpPrCheck`; PR required checks must come from Actions. The shared library no longer exposes Jenkins build-node selection.

## Build and test this library

Run `bash tests/run.sh` (Docker required). It exercises deployment shell behavior, GHCR image waiting, helper pure functions, Groovy compilation, shellcheck, and migration guard utilities. Keep consumer Actions workflows authoritative for CI; this test runner is a maintainer validation tool, not a Jenkins job.
