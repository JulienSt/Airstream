import importlib.util
import pathlib
import unittest
import subprocess
import tempfile
from unittest.mock import patch


SCRIPT = pathlib.Path(__file__).parents[1] / "report_port_diff.py"
SPEC = importlib.util.spec_from_file_location("report_port_diff", SCRIPT)
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class DiffBreakdownTest(unittest.TestCase):

    def test_separates_pure_moves_mechanical_commits_and_genuine_commits(self):
        records = CHECKER.parse_name_status("R100\told.scala\tnew.scala\nM\tCore.scala\n")
        categories = CHECKER.breakdown(records, ["mechanical", "feature"], {"mechanical"})

        self.assertEqual(categories.pure_moves, (("old.scala", "new.scala"),))
        self.assertEqual(categories.mechanical_commits, ("mechanical",))
        self.assertEqual(categories.genuine_commits, ("feature",))

    def test_does_not_count_an_edited_rename_as_a_pure_move(self):
        records = CHECKER.parse_name_status("R087\told.scala\tnew.scala\n")

        self.assertEqual(CHECKER.breakdown(records, [], set()).pure_moves, ())


class SharedProductionInventoryTest(unittest.TestCase):

    def test_counts_each_touched_shared_production_file_once(self):
        records = CHECKER.parse_name_status(
            "M\tsrc/main/scala/a/A.scala\nM\tsrc/test/scala/a/ASpec.scala\nM\tsrc/main/scala/a/A.scala\n"
        )

        self.assertEqual(CHECKER.shared_production_paths(records), ("src/main/scala/a/A.scala",))

    def test_uses_the_destination_of_a_renamed_shared_file(self):
        records = CHECKER.parse_name_status("R100\tsrc/main/scala/a/A.scala\t.js/src/main/scala/a/A.scala\n")

        self.assertEqual(CHECKER.shared_production_paths(records), ())


class MoveRecognitionTest(unittest.TestCase):

    def test_accepts_only_git_recognized_hundred_percent_moves(self):
        records = CHECKER.parse_name_status("R100\ta\tb\nR099\tc\td\n")

        self.assertEqual(CHECKER.pure_moves(records), (("a", "b"),))

    def test_preserves_both_move_paths_for_a_reviewable_report(self):
        record = CHECKER.parse_name_status("R100\tsrc/Old.scala\t.js/src/New.scala\n")[0]

        self.assertEqual((record.source, record.destination), ("src/Old.scala", ".js/src/New.scala"))


class GrowthBudgetTest(unittest.TestCase):

    def test_accepts_a_metric_at_its_explicit_budget(self):
        CHECKER.assert_within_budget("genuine commits", 12, 12)

    def test_rejects_growth_and_names_the_metric(self):
        with self.assertRaisesRegex(CHECKER.DiffGrowthError, "shared production files"):
            CHECKER.assert_within_budget("shared production files", 13, 12)


class UpstreamMergeTest(unittest.TestCase):

    def test_reports_only_port_commits_after_merging_a_new_upstream_baseline(self):
        with tempfile.TemporaryDirectory() as temporary:
            repository = pathlib.Path(temporary)

            def git(*args):
                return subprocess.check_output(["git", *args], cwd=repository, text=True, stderr=subprocess.DEVNULL).strip()

            git("init", "-b", "upstream")
            git("config", "user.name", "Port checker test")
            git("config", "user.email", "port-checker@example.invalid")
            (repository / "base.txt").write_text("base\n")
            git("add", ".")
            git("commit", "-m", "baseline")
            git("switch", "-c", "port")
            (repository / "port.txt").write_text("portable implementation\n")
            git("add", ".")
            git("commit", "-m", "native support")
            git("switch", "upstream")
            (repository / "upstream.txt").write_text("upstream feature\n")
            git("add", ".")
            git("commit", "-m", "new upstream feature")
            baseline = git("rev-parse", "HEAD")
            git("switch", "port")
            git("merge", "--no-ff", "upstream", "-m", "merge upstream")

            with patch.object(CHECKER, "BASELINE_COMMIT", baseline):
                result = CHECKER.report(repository, check_budget=False)

            self.assertIn("Genuine intervention commits: 1\n", result)
            self.assertIn("Genuine intervention line changes: 1\n", result)


if __name__ == "__main__":
    unittest.main()
