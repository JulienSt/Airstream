import importlib.util
import pathlib
import unittest


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


if __name__ == "__main__":
    unittest.main()
