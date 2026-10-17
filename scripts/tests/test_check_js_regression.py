import importlib.util
import pathlib
import unittest


SCRIPT = pathlib.Path(__file__).parents[1] / "check_js_regression.py"
SPEC = importlib.util.spec_from_file_location("check_js_regression", SCRIPT)
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class SizeComparisonTest(unittest.TestCase):

    def test_accepts_the_one_percent_boundary(self):
        CHECKER.assert_size_within_tolerance(10_000, 10_100, "raw", tolerance=0.01)

    def test_rejects_a_payload_beyond_the_boundary(self):
        with self.assertRaisesRegex(CHECKER.RegressionError, "gzip"):
            CHECKER.assert_size_within_tolerance(10_000, 10_101, "gzip", tolerance=0.01)


if __name__ == "__main__":
    unittest.main()
