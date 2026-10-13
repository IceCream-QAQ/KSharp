#!/usr/bin/env python3
"""Build real, pinned dotkt inputs for the native Rider experiment; no synthetic KLIB stubs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--dotkt-source", type=Path, required=True)
parser.add_argument("--dotnet", type=Path, default=Path.home() / ".dotnet/dotnet")
parser.add_argument("--jdk21", type=Path)
parser.add_argument("--gradle", type=Path, help="Use an existing Gradle installation instead of the checkout wrapper")
parser.add_argument("--reuse-toolchain", action="store_true", help="Reuse existing kotc/dll2klib/stdlib outputs from this pinned checkout")
parser.add_argument("--rider", type=Path, default=Path.home() / "Applications/Rider.app/Contents")
args = parser.parse_args()
here = Path(__file__).resolve().parent
root = args.dotkt_source.resolve()
out = here / "build"
out.mkdir(exist_ok=True)
if args.jdk21 is None:
    args.jdk21 = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip())
env = dict(os.environ, JAVA_HOME=str(args.jdk21), DOTNET_CLI_TELEMETRY_OPTOUT="1", DOTNET_NOLOGO="1")
env.setdefault("DOTNET_CLI_HOME", str(out / "dotnet-home"))
env.setdefault("NUGET_PACKAGES", str(out / "nuget"))
commands = []

def run(label, command, cwd=here, extra_env=None):
    command = list(map(str, command))
    commands.append({"label": label, "cwd": str(cwd), "command": command})
    print(label, flush=True)
    with (out / f"{label}.log").open("w") as log:
        result = subprocess.run(command, cwd=cwd, env=env | (extra_env or {}), stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        print((out / f"{label}.log").read_text()[-9000:])
        raise SystemExit(result.returncode)

kotc = root / "toolchain/kotc/build/install/kotc/bin/kotc"
converter = out / "dll2klib/dll2klib.dll"
stdlib = root / "build/clr-stdlib-frontend-klib/kotlin-stdlib-clr-frontend.klib"
if not args.reuse_toolchain:
    gradle = [args.gradle] if args.gradle else ["bash", root / "gradlew"]
    run("build-kotc", [*gradle, "--no-daemon", ":kotc:installDist"], root)
    # A real cwd avoids /tmp versus /private/tmp prefix mismatches in protoc on macOS.
    run("build-dll2klib", [args.dotnet, "build", "-c", "Release", "-o", converter.parent, "--nologo", "-p:UseSharedCompilation=false"], root / "toolchain/dll2klib")
    source = root / "libraries/stdlib"
    common = sum([sorted((source / p).rglob("*.kt")) for p in ["common/src", "clr/common", "src", "unsigned/src"]], [])
    clr = [p for p in sorted((source / "clr").rglob("*.kt")) if not p.is_relative_to(source / "clr/common")]
    if not common or not clr: raise SystemExit("The complete dotkt stdlib sources are required")
    stdlib.parent.mkdir(parents=True, exist_ok=True)
    # Mirrors scripts/lib.sh and build-stdlib-klib.sh at the pinned commit; works with macOS Bash 3.
    opt_ins = "kotlin.ExperimentalUnsignedTypes,kotlin.experimental.ExperimentalTypeInference,kotlin.contracts.ExperimentalContracts,kotlin.ExperimentalMultiplatform,kotlin.ExperimentalStdlibApi,kotlin.ExperimentalSubclassOptIn,kotlin.io.encoding.ExperimentalEncodingApi,kotlin.time.ExperimentalTime,kotlin.uuid.ExperimentalUuidApi"
    run("build-stdlib-klib", ["bash", kotc, *common, *clr, "-Xallow-kotlin-package", "-Xexpect-actual-classes",
        "-Xstdlib-compilation", "-Xcontext-parameters", "-Xreturn-value-checker=check", "-XXLanguage:+UnnamedLocalVariables",
        "-Xcommon-sources=" + ",".join(map(str, common)), "-opt-in=" + opt_ins,
        "-Xfragments=common,clr", "-Xfragment-refines=clr:common",
        *["-Xfragment-sources=common:" + str(p) for p in common], *["-Xfragment-sources=clr:" + str(p) for p in clr],
        "-d", stdlib], root, {"DOTKT_BUILD_KLIB": "1"})
for required in [kotc, converter, stdlib]:
    if not required.is_file(): raise SystemExit(f"Missing input: {required}")

project = here / "fixtures/mixed/Api/Api.csproj"
run("build-fixture", [args.dotnet, "build", project, "--nologo", "-v:q", "-p:UseSharedCompilation=false"])
reference_command = [str(args.dotnet), "msbuild", str(project), "-t:ResolveReferences",
                     "-getItem:ReferencePath,ReferencePathWithRefAssemblies", "-getProperty:TargetFramework"]
references = json.loads(subprocess.check_output(reference_command, cwd=project.parent, env=env, text=True))
(out / "references.json").write_text(json.dumps(references, indent=2) + "\n")
items = references["Items"]
selected = items["ReferencePathWithRefAssemblies"] or items["ReferencePath"]
assemblies = [Path(item["FullPath"]) for item in selected]
assemblies.append(project.parent / "bin/Debug/net10.0/Api.dll")
response = out / "references.rsp"
response.write_text("\n".join(map(str, assemblies)) + "\n")
run("dll2klib", [args.dotnet, converter, "--out", out / "klib", "--jobs", "4", "@" + str(response)])
# Use the selected reference set, not an arbitrary directory glob that could retain removed outputs.
klibs = [out / "klib" / (assembly.stem + ".klib") for assembly in assemblies]
frontend = [stdlib, *klibs]
run("kotc-baseline", ["bash", kotc, here / "fixtures/mixed/App/Program.kt", "-classpath",
                      os.pathsep.join(map(str, frontend)), "-d", out / "bir"])
run("build-backend", [args.dotnet, "build", here / "backend/NativeProbe.Backend.csproj", "--nologo", "-v:q",
                      "-p:UseSharedCompilation=false",
                      "-p:RiderHost=" + str(args.rider / "lib/ReSharperHost")])
snapshot = {
    "expectedDotktCommit": "400ab50472c0839a9b7de6a94a18db4bc9ad4bb5",
    "sourceDigests": {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in [
        "toolchain/kotc/build.gradle.kts", "toolchain/dll2klib/Program.cs",
        "toolchain/kotc/src/main/kotlin/kotc/pipeline/ClrCliPipeline.kt",
        "toolchain/kotc/src/main/kotlin/kotc/pipeline/ClrStaticScopes.kt"]},
    "dotktSource": str(root), "targetFramework": references["Properties"]["TargetFramework"],
    "sourceRoot": str(here / "fixtures/mixed/App"),
    "sourceFiles": [str(here / "fixtures/mixed/App/Program.kt")], "stdlib": str(stdlib),
    "references": [{"sourceAssembly": str(assembly), "klib": str(klib),
                    "sha256": hashlib.sha256(klib.read_bytes()).hexdigest()} for assembly, klib in zip(assemblies, klibs)],
    "commands": commands,
}
(out / "input-snapshot.json").write_text(json.dumps(snapshot, indent=2) + "\n")
print(f"Prepared {len(klibs)} reference KLIBs plus the CLR stdlib; compiler baseline passed.")
