#!/usr/bin/env python3
"""Load Airstream from a consumer build without inheriting its plugins or downloads."""

import argparse
import json
import pathlib
import subprocess
import tempfile


def verify(repository):
    with tempfile.TemporaryDirectory(prefix="airstream-consumer-") as temporary:
        consumer = pathlib.Path(temporary)
        project = consumer / "project"
        project.mkdir()
        (project / "build.properties").write_text("sbt.version=1.12.0\n")
        (project / "plugins.sbt").write_text(
            'addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.11")\n'
        )
        (consumer / "build.sbt").write_text(
            'lazy val airstreamNative = ProjectRef(file(' + json.dumps(str(repository)) + '), "airstreamNative")\n'
            'lazy val consumer = project.in(file(".")).aggregate(airstreamNative)\n'
            'ThisBuild / scalaVersion := "3.8.2"\n'
        )
        result = subprocess.run(
            ["sbt", "-batch", "projects"], cwd=consumer, text=True,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        )
        if result.returncode != 0:
            print(result.stdout)
            raise SystemExit("External consumer build failed to load")
        if (consumer / ".buildkit").exists():
            raise SystemExit("Airstream's formatter downloads leaked into the consumer build")
        if "airstreamNative" not in result.stdout:
            raise SystemExit("Consumer did not load the referenced Airstream Native project")
        print("External consumer build loads without Airstream plugins or downloads in the consumer")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[1])
    verify(parser.parse_args().repository.resolve())
