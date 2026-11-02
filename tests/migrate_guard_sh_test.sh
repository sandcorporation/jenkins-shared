#!/usr/bin/env bash
# migrate-guard.sh 시험: 기준..HEAD 에서 「새로 추가된」 migration 만 고르는가. git·python3 이 있는 호스트에서 돈다.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
sh_guard="$root/resources/sandcorp/migrate-guard.sh"
work="$(mktemp -d)"
trap 'chmod -R u+w "$work"; find "$work" -mindepth 1 -delete; rmdir "$work"' EXIT
cd "$work"
git init -q -b main .
git config user.email t@t; git config user.name t
mkdir -p app/migrations
: > app/migrations/__init__.py
cat > app/migrations/0001_initial.py <<'EOF'
from django.db import migrations, models
class Migration(migrations.Migration):
    operations = [migrations.CreateModel(name='Post', fields=[('id', models.AutoField(primary_key=True)), ('old', models.IntegerField(null=True))])]
EOF
git add -A; git commit -qm base
base=$(git rev-parse HEAD)

n=0
expect() { # 이름 기대종료코드 기대문구
  local code=0 out
  out=$(bash "$sh_guard" "$base" 2>&1) || code=$?
  [ "$code" = "$2" ] || { echo "실패 $1: 종료 $code (기대 $2)"; echo "$out"; exit 1; }
  grep -q -- "$3" <<<"$out" || { echo "실패 $1: '$3' 없음"; echo "$out"; exit 1; }
  n=$((n + 1))
}

expect '새 migration 없음' 0 '새 migration 없음'

# 이미 있던 migration 을 고친 것은 대상이 아니다(옛 파일에 RemoveField 를 넣어도 통과).
cat >> app/migrations/0001_initial.py <<'EOF'
# migrations.RemoveField(model_name='post', name='old') 메모
EOF
cat > app/notes.py <<'EOF'
x = 1
EOF
git add -A; git commit -qm 'edit old'
expect '고친 파일·migration 밖 파일은 무시' 0 '새 migration 없음'

cat > app/migrations/0002_safe.py <<'EOF'
from django.db import migrations, models
class Migration(migrations.Migration):
    operations = [migrations.AddField(model_name='post', name='a', field=models.IntegerField(null=True))]
EOF
git add -A; git commit -qm safe
expect '안전한 새 migration' 0 '통과  app/migrations/0002_safe.py'

cat > app/migrations/0003_drop.py <<'EOF'
from django.db import migrations
class Migration(migrations.Migration):
    operations = [migrations.RemoveField(model_name='post', name='old')]
EOF
git add -A; git commit -qm drop
expect '파괴적 새 migration' 1 '파괴  app/migrations/0003_drop.py:3 RemoveField post.old'

sed -i '1i # sandcorp: destructive-ok — post.old 읽기는 앞 배포에서 걷어냈다' app/migrations/0003_drop.py
git add -A; git commit -qm mark
expect '통과 표시' 0 '표시  app/migrations/0003_drop.py'

code=0; bash "$sh_guard" no-such-rev >/dev/null 2>&1 || code=$?
[ "$code" = 2 ] || { echo "실패 없는 기준: 종료 $code"; exit 1; }
n=$((n + 1))

echo "migrate-guard.sh 시험: 통과 $n"
