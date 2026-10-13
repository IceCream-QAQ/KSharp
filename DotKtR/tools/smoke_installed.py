#!/usr/bin/env python3
"""Install the built ZIP in a fresh Rider sandbox and load the separate integration driver."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile
from prepare_compat_host import prepare

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--rider", type=Path, default=Path.home() / "Applications/Rider.app/Contents")
parser.add_argument("--idea", type=Path, default=Path.home() / "Applications/IntelliJ IDEA.app/Contents")
parser.add_argument("--plugin", type=Path, default=root / "build/distributions/DotKtR-0.1.1-dev.zip")
parser.add_argument("--driver", type=Path, default=root / "build/libs/DotKtR-0.1.1-dev-integration-driver.jar")
parser.add_argument("--fixture", type=Path, default=root / "spikes/native-psi/fixtures/aspnet")
parser.add_argument("--foreground", action="store_true", help="Keep the launcher attached and record the IDE exit status")
parser.add_argument("--license-config", type=Path, default=Path.home() / "Library/Application Support/JetBrains/Rider2026.2")
args = parser.parse_args()
out = Path(tempfile.mkdtemp(prefix="installed-smoke-", dir=root / "build"))
for name in ("plugins", "config", "system", "log"):
    (out / name).mkdir()
with zipfile.ZipFile(args.plugin) as archive:
    for name in archive.namelist():
        if Path(name).is_absolute() or ".." in Path(name).parts:
            raise ValueError("Invalid plugin archive path")
    archive.extractall(out / "plugins")
prepare(args.idea, args.rider, out / "plugins")
driver = out / "plugins/dotktr-integration/lib"
driver.mkdir(parents=True)
shutil.copy2(args.driver, driver / "driver.jar")
shutil.copytree(args.fixture, out / "fixture", ignore=shutil.ignore_patterns(".idea", "bin", "obj", "*.iml", "*.user"))
license_file = args.license_config / "rider.key"
if license_file.is_file():
    (out / "config/rider.key").symlink_to(license_file)
info = json.loads((args.rider / "Resources/product-info.json").read_text())
launch = next(item for item in info["launch"] if item["os"] == "macOS" and item["arch"] == "aarch64")
vm = [arg.replace("$APP_PACKAGE", str(args.rider.parent)) for arg in launch["additionalJvmArguments"]]
command = [str(args.rider / "jbr/Contents/Home/bin/java"), "-Xmx2048m", *vm,
           "-Didea.auto.reload.plugins=false", "-Didea.trust.all.projects=true", "-Didea.initially.ask.config=false",
           "-Djb.consents.confirmation.enabled=false", "-Dide.experimental.ui.onboarding=false",
           "-Dkotlin.index.non.source.roots=true",
           "-Didea.log.debug.categories=#dev.dotktr.ClrSessionConfigurator",
           f"-Didea.config.path={out / 'config'}", f"-Didea.system.path={out / 'system'}",
           f"-Didea.log.path={out / 'log'}", f"-Didea.plugins.path={out / 'plugins'}",
           f"-Ddotktr.integration.report={out / 'report.json'}", "-cp",
           os.pathsep.join(str(args.rider / "lib" / jar) for jar in launch["bootClassPathJarNames"]),
           launch["mainClass"], str(out / "fixture/DotKt-AspNetCore.sln")]
environment = os.environ.copy()
environment["JAVA_HOME"] = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip()
environment["PATH"] = str(Path.home() / ".dotnet") + os.pathsep + environment["PATH"]
with (out / "console.log").open("w") as log:
    process = subprocess.Popen(command, cwd=args.rider / "bin", env=environment, stdout=log, stderr=subprocess.STDOUT)
(out / "launch.json").write_text(json.dumps({"pid": process.pid, "command": command, "plugin": str(args.plugin.resolve())}, indent=2) + "\n")
print(json.dumps({"sandbox": str(out), "pid": process.pid, "report": str(out / "report.json")}, indent=2), flush=True)
if args.foreground:
    exit_code = process.wait()
    (out / "exit.json").write_text(json.dumps({"exitCode": exit_code}) + "\n")
    raise SystemExit(exit_code)
