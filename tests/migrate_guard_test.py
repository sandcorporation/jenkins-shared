"""resources/sandcorp/migrate-guard.py 판정 규칙 시험. 바깥 경계(CLI: 파일을 주면 종료 코드·출력)만 본다.

    python3 -I tests/migrate_guard_test.py      (tests/run.sh 는 python:3.9-slim 도커에서 돌린다 — 맥 노드의 python3 3.9 호환)
"""
import os
import subprocess
import sys
import tempfile
import textwrap
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GUARD = os.path.join(ROOT, "resources", "sandcorp", "migrate-guard.py")

HEAD = "from django.db import migrations, models\n\n\nclass Migration(migrations.Migration):\n"


def migration(ops, comment=""):
    body = textwrap.indent(textwrap.dedent(ops).strip(), " " * 8)
    return f"{comment}{HEAD}    dependencies = []\n    operations = [\n{body}\n    ]\n"


class Guard(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = os.path.join(self.tmp.name, "app", "migrations")
        os.makedirs(self.dir)
        open(os.path.join(self.dir, "__init__.py"), "w").close()

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, name, text):
        path = os.path.join(self.dir, name)
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        return path

    def run_guard(self, *paths):
        r = subprocess.run([sys.executable, "-I", GUARD, *paths], capture_output=True, text=True, cwd=self.tmp.name)
        return r.returncode, r.stdout + r.stderr

    def assertDestructive(self, ops, *expect, prior=None):
        if prior:
            self.write("0001_initial.py", migration(prior))
        code, out = self.run_guard(self.write("0002_new.py", migration(ops)))
        self.assertEqual(code, 1, out)
        for e in expect:
            self.assertIn(e, out)
        return out

    def assertSafe(self, ops, prior=None):
        if prior:
            self.write("0001_initial.py", migration(prior))
        code, out = self.run_guard(self.write("0002_new.py", migration(ops)))
        self.assertEqual(code, 0, out)
        return out

    # --- 파괴적 연산

    def test_remove_field(self):
        self.assertDestructive("migrations.RemoveField(model_name='post', name='old'),", "RemoveField", "post.old", "0002_new.py:")

    def test_delete_model(self):
        self.assertDestructive("migrations.DeleteModel(name='Post'),", "DeleteModel", "Post")

    def test_rename_field(self):
        self.assertDestructive("migrations.RenameField(model_name='post', old_name='a', new_name='b'),", "RenameField", "post.a")

    def test_rename_model(self):
        self.assertDestructive("migrations.RenameModel(old_name='Post', new_name='Article'),", "RenameModel", "Post")

    def test_positional_args_and_bare_names(self):
        # from django.db.migrations import RemoveField 처럼 이름만 쓴 것과 위치 인자도 잡는다.
        self.assertDestructive("RemoveField('post', 'old'),", "RemoveField", "post.old")

    def test_alter_field_null_to_not_null(self):
        prior = "migrations.CreateModel(name='Post', fields=[('id', models.AutoField(primary_key=True)), ('title', models.CharField(max_length=10, null=True))]),"
        self.assertDestructive(
            "migrations.AlterField(model_name='post', name='title', field=models.CharField(max_length=10)),",
            "AlterField", "post.title", "null", prior=prior,
        )

    def test_alter_field_null_to_not_null_after_add_field(self):
        prior = "migrations.AddField(model_name='post', name='slug', field=models.SlugField(null=True)),"
        self.assertDestructive("migrations.AlterField(model_name='post', name='slug', field=models.SlugField(null=False)),", "post.slug", prior=prior)

    def test_alter_field_follows_rename_in_prior(self):
        prior = """
            migrations.AddField(model_name='post', name='a', field=models.IntegerField(null=True)),
            migrations.RenameField(model_name='post', old_name='a', new_name='b'),
        """
        self.assertDestructive("migrations.AlterField(model_name='post', name='b', field=models.IntegerField()),", "post.b", prior=prior)

    def test_alter_field_unknown_prior_fails_closed(self):
        self.assertDestructive("migrations.AlterField(model_name='post', name='title', field=models.CharField(max_length=20)),", "post.title", "찾지 못")

    def test_add_not_null_field_without_db_default_warns_only(self):
        out = self.assertSafe("migrations.AddField(model_name='post', name='n', field=models.IntegerField(default=0)),")
        self.assertIn("경고", out)
        self.assertIn("post.n", out)
        self.assertIn("db_default", out)

    def test_run_sql_drop(self):
        self.assertDestructive("migrations.RunSQL('ALTER TABLE post DROP COLUMN old'),", "RunSQL", "DROP")

    def test_run_sql_rename_in_list_kwarg(self):
        self.assertDestructive("migrations.RunSQL(sql=['SELECT 1', ('alter table post rename column a to b', [])]),", "RunSQL", "RENAME")

    def test_run_sql_not_literal_fails_closed(self):
        self.assertDestructive("migrations.RunSQL(SQL),", "RunSQL", "읽을 수 없")

    def test_separate_database_and_state_database_ops(self):
        self.assertDestructive(
            "migrations.SeparateDatabaseAndState(database_operations=[migrations.RemoveField(model_name='post', name='x')], state_operations=[]),",
            "RemoveField", "post.x",
        )

    def test_several_findings_listed(self):
        out = self.assertDestructive("""
            migrations.RemoveField(model_name='post', name='a'),
            migrations.DeleteModel(name='Tag'),
        """, "post.a", "Tag")
        self.assertIn("2", out)

    # --- 안전한 연산

    def test_safe_ops(self):
        self.assertSafe("""
            migrations.CreateModel(name='Tag', fields=[('id', models.AutoField(primary_key=True)), ('name', models.CharField(max_length=10))]),
            migrations.AddField(model_name='post', name='a', field=models.IntegerField(null=True)),
            migrations.AddField(model_name='post', name='b', field=models.IntegerField(db_default=0)),
            migrations.AddField(model_name='post', name='tags', field=models.ManyToManyField(to='app.tag')),
            migrations.AddIndex(model_name='post', index=models.Index(fields=['a'], name='a_idx')),
            migrations.RunPython(migrations.RunPython.noop),
            migrations.RunSQL('CREATE INDEX x ON post (a)', reverse_sql='DROP INDEX x ON post'),
            migrations.SeparateDatabaseAndState(state_operations=[migrations.RemoveField(model_name='post', name='z')]),
        """)
        self.assertNotIn("경고", self.run_guard(os.path.join(self.dir, "0002_new.py"))[1])

    def test_alter_field_not_null_to_not_null(self):
        prior = "migrations.AddField(model_name='post', name='title', field=models.CharField(max_length=10, db_default=''))"
        self.assertSafe("migrations.AlterField(model_name='post', name='title', field=models.CharField(max_length=20)),", prior=prior)

    def test_alter_field_to_nullable(self):
        self.assertSafe("migrations.AlterField(model_name='post', name='title', field=models.CharField(max_length=20, null=True)),")

    def test_sql_comment_and_identifier_not_matched(self):
        self.assertSafe("migrations.RunSQL('-- drop 은 다음 배포에서\\nUPDATE post SET dropped_at = NULL'),")

    # --- 통과 표시

    def test_mark_passes_with_reason(self):
        path = self.write("0002_new.py", migration(
            "migrations.RemoveField(model_name='post', name='old'),",
            comment="# sandcorp: destructive-ok — post.old 읽기는 #12 배포(2026-11-01)에서 걷어냈다\n",
        ))
        code, out = self.run_guard(path)
        self.assertEqual(code, 0, out)
        self.assertIn("#12 배포", out)

    def test_mark_with_ascii_dash(self):
        path = self.write("0002_new.py", migration(
            "migrations.DeleteModel(name='Tag'),", comment="# sandcorp: destructive-ok - Tag 는 v3 배포부터 안 쓴다\n"))
        self.assertEqual(self.run_guard(path)[0], 0)

    def test_mark_without_reason_does_not_pass(self):
        path = self.write("0002_new.py", migration("migrations.DeleteModel(name='Tag'),", comment="# sandcorp: destructive-ok\n"))
        code, out = self.run_guard(path)
        self.assertEqual(code, 1, out)
        self.assertIn("이유", out)

    # --- 그 밖

    def test_syntax_error_fails(self):
        code, out = self.run_guard(self.write("0002_new.py", "class Migration(:\n"))
        self.assertEqual(code, 1, out)

    def test_no_files_passes(self):
        self.assertEqual(self.run_guard()[0], 0)

    def test_multiple_files_one_bad(self):
        ok = self.write("0002_ok.py", migration("migrations.AddField(model_name='post', name='a', field=models.IntegerField(null=True)),"))
        bad = self.write("0003_bad.py", migration("migrations.DeleteModel(name='Tag'),"))
        code, out = self.run_guard(ok, bad)
        self.assertEqual(code, 1, out)
        self.assertIn("0003_bad.py", out)


if __name__ == "__main__":
    unittest.main(verbosity=1)
