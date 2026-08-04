import importlib.util
import pathlib
import unittest


SCRIPT = pathlib.Path(__file__).parents[1] / "check_js_regression.py"
SPEC = importlib.util.spec_from_file_location("check_js_regression", SCRIPT)
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class MimaProblemComparisonTest(unittest.TestCase):

    def test_normalizes_ansi_and_preserves_duplicate_problems(self):
        output = "\u001b[0m[debug\u001b[0m] problem found: first\n[debug] problem found: first\n"

        self.assertEqual(CHECKER.normalize_mima_problems(output), {"first": 2})

    def test_ignores_only_the_named_current_problem(self):
        baseline = "[debug] problem found: stable\n"
        current = "[debug] problem found: stable\n[debug] problem found: allowed\n"

        CHECKER.assert_no_new_mima_problems(baseline, current, {"allowed"})

    def test_rejects_an_added_binary_problem(self):
        baseline = "[debug] problem found: stable\n"
        current = "[debug] problem found: stable\n[debug] problem found: broken\n"

        with self.assertRaisesRegex(CHECKER.RegressionError, "broken"):
            CHECKER.assert_no_new_mima_problems(baseline, current, set())

    def test_accepts_a_removed_binary_problem(self):
        baseline = "[debug] problem found: obsolete\n[debug] problem found: stable\n"
        current = "[debug] problem found: stable\n"

        CHECKER.assert_no_new_mima_problems(baseline, current, set())


class SizeComparisonTest(unittest.TestCase):

    def test_accepts_the_one_percent_boundary(self):
        CHECKER.assert_size_within_tolerance(10_000, 10_100, "raw", tolerance=0.01)

    def test_rejects_a_payload_beyond_the_boundary(self):
        with self.assertRaisesRegex(CHECKER.RegressionError, "gzip"):
            CHECKER.assert_size_within_tolerance(10_000, 10_101, "gzip", tolerance=0.01)


if __name__ == "__main__":
    unittest.main()
