import importlib.util
import pathlib
import tempfile
import unittest


SCRIPT = pathlib.Path(__file__).parents[1] / "check_platform_boundaries.py"
SPEC = importlib.util.spec_from_file_location("check_platform_boundaries", SCRIPT)
CHECKER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECKER)


class RunningPlatformQueryTest(unittest.TestCase):

    def test_rejects_a_scalajs_linking_query_in_shared_production_code(self):
        violations = CHECKER.scan_source("val native = scala.scalajs.LinkingInfo.developmentMode")

        self.assertIn("scala.scalajs", violations)

    def test_rejects_a_runtime_property_query_even_when_hidden_in_a_branch(self):
        violations = CHECKER.scan_source('if (System.getProperty("java.vm.name") == "Scala Native") native()')

        self.assertIn("System.getProperty", violations)


class SourceSetSelectionTest(unittest.TestCase):

    def test_ignores_platform_implementation_source_sets(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root / ".native/src/main/scala/example/Platform.scala"
            source.parent.mkdir(parents=True)
            source.write_text("val target = scala.scalanative.meta.LinktimeInfo.isWindows\n")

            self.assertEqual(CHECKER.find_violations(root), ())

    def test_rejects_the_same_platform_decision_in_the_shared_source_set(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root / "src/main/scala/example/Platform.scala"
            source.parent.mkdir(parents=True)
            source.write_text("val target = scala.scalanative.meta.LinktimeInfo.isWindows\n")

            self.assertEqual(CHECKER.find_violations(root)[0].path, source)


class RegressionGateTest(unittest.TestCase):

    def test_clean_shared_sources_pass_the_gate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root / "src/main/scala/example/Core.scala"
            source.parent.mkdir(parents=True)
            source.write_text("val result = platform.JsArray(1, 2, 3)\n")
            (root / "CONTRIBUTING.md").write_text(CHECKER.REQUIRED_MERGE_AGREEMENT)

            self.assertEqual(CHECKER.check_repository(root), ())

    def test_each_violation_names_its_file_and_forbidden_probe(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root / "src/main/scala/example/Core.scala"
            source.parent.mkdir(parents=True)
            source.write_text("val target = isScalaJS\n")
            (root / "CONTRIBUTING.md").write_text(CHECKER.REQUIRED_MERGE_AGREEMENT)

            violation = CHECKER.check_repository(root)[0]
            self.assertEqual((violation.path, violation.probe), (source, "isScalaJS"))


class MergeAgreementTest(unittest.TestCase):

    def test_accepts_the_recorded_source_set_rule(self):
        CHECKER.assert_merge_agreement("before\n" + CHECKER.REQUIRED_MERGE_AGREEMENT + "\nafter")

    def test_rejects_contributing_rules_that_do_not_bind_shared_sources(self):
        with self.assertRaisesRegex(CHECKER.PlatformBoundaryError, "merge agreement"):
            CHECKER.assert_merge_agreement("Platform support is implemented somehow.")


if __name__ == "__main__":
    unittest.main()
