#!/usr/bin/env python3

import argparse
import gzip
import pathlib
import os
import subprocess
import tempfile


BASELINE_COMMIT = "aa6818b7f523244f5abab29e4b56136fcc31183b"
SIZE_TOLERANCE = 0.01


class RegressionError(RuntimeError):
    pass


def assert_size_within_tolerance(baseline, current, label, tolerance=SIZE_TOLERANCE):
    maximum = baseline * (1 + tolerance)
    if current > maximum:
        change = (current - baseline) / baseline * 100
        raise RegressionError(
            f"{label} JavaScript grew from {baseline} to {current} bytes ({change:.3f}%, limit {tolerance * 100:.1f}%)"
        )


def run(command, cwd, allow_failure=False, show_output=False, environment=None):
    print(f"[{cwd.name}] {' '.join(command)}", flush=True)
    result = subprocess.run(
        command, cwd=cwd, env=environment, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT
    )
    if show_output or (result.returncode != 0 and not allow_failure):
        print(result.stdout)
    if result.returncode != 0 and not allow_failure:
        raise RegressionError(f"Command failed with exit code {result.returncode}: {' '.join(command)}")
    return result


def environment_without_ci():
    environment = os.environ.copy()
    environment.pop("CI", None)
    return environment


def write_consumer(path):
    path.write_text(
        """import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.airstream.state.Var

object JsRegressionMain {
  def main(args: Array[String]): Unit = {
    val owner = new ManualOwner
    val total = Var(0)
    val input = new EventBus[Int]
    input.events.foreach(value => total.update(_ + value))(owner)
    input.emit(1)
    println(total.now())
    owner.killSubscriptions()
  }
}
"""
    )


def link_consumer(project_root, source, current):
    quoted_source = str(source).replace("\\", "\\\\").replace('"', '\\"')
    if current:
        source_setting = (
            'set LocalProject("airstreamJS") / Compile / sources := '
            f'(LocalProject("airstreamJS") / Compile / sources).value :+ file("{quoted_source}")'
        )
        main_setting = 'set LocalProject("airstreamJS") / Compile / mainClass := Some("JsRegressionMain")'
        task = "airstreamJS/Compile/fullLinkJS"
        output = project_root / ".js/target/scala-2.13/airstream-opt/main.js"
    else:
        source_setting = f'set Compile / sources := (Compile / sources).value :+ file("{quoted_source}")'
        main_setting = 'set Compile / mainClass := Some("JsRegressionMain")'
        task = "Compile/fullLinkJS"
        output = project_root / "target/scala-2.13/airstream-opt/main.js"
    run(
        ["sbt", "-batch", "++2.13.18", source_setting, main_setting, task],
        project_root,
        environment=None if current else environment_without_ci(),
    )
    if not output.is_file():
        raise RegressionError(f"Scala.js linker did not produce {output}")
    return output.read_bytes()


def verify(repository, baseline_commit):
    with tempfile.TemporaryDirectory(prefix="airstream-js-regression-") as temporary:
        temporary_root = pathlib.Path(temporary)
        baseline_root = temporary_root / "baseline"
        consumer = temporary_root / "JsRegressionMain.scala"
        write_consumer(consumer)

        run(["git", "clone", "--shared", "--no-checkout", str(repository), str(baseline_root)], repository)
        run(["git", "checkout", "--detach", baseline_commit], baseline_root)

        run(["sbt", "-batch", "+airstreamJS/test"], repository)

        baseline_js = link_consumer(baseline_root, consumer, current=False)
        current_js = link_consumer(repository, consumer, current=True)
        baseline_gzip = gzip.compress(baseline_js, mtime=0)
        current_gzip = gzip.compress(current_js, mtime=0)
        assert_size_within_tolerance(len(baseline_js), len(current_js), "raw")
        assert_size_within_tolerance(len(baseline_gzip), len(current_gzip), "gzip")
        print(
            "Optimized minimal consumer: "
            f"raw {len(baseline_js)} -> {len(current_js)} bytes; "
            f"gzip {len(baseline_gzip)} -> {len(current_gzip)} bytes"
        )


def main():
    parser = argparse.ArgumentParser(description="Compare the Scala.js port with the unmodified Airstream baseline")
    parser.add_argument("--baseline", default=BASELINE_COMMIT, help="unmodified upstream git commit")
    arguments = parser.parse_args()
    repository = pathlib.Path(__file__).resolve().parents[1]
    verify(repository, arguments.baseline)


if __name__ == "__main__":
    main()
