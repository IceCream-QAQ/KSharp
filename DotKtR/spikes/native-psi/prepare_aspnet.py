#!/usr/bin/env python3
"""Prepare a real DotKt/ASP.NET Core project and its public MSBuild analysis snapshot."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("--dotkt-source", type=Path, default=Path("/private/tmp/dotktr-dotkt-400ab504"))
parser.add_argument("--dotnet", type=Path, default=Path.home() / ".dotnet/dotnet")
parser.add_argument("--references-only", action="store_true")
args = parser.parse_args()
here = Path(__file__).resolve().parent
root = args.dotkt_source.resolve()
fixture = here / "fixtures/aspnet"
project = fixture / "KotlinWeb/KotlinWeb.ktproj"
out = here / "build/aspnet"
out.mkdir(parents=True, exist_ok=True)
jdk = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip()
env = os.environ.copy()
env.update(JAVA_HOME=jdk, DOTNET_CLI_HOME="/tmp/dotktr-dotnet-home", NUGET_PACKAGES="/tmp/dotktr-nuget",
           DOTNET_CLI_TELEMETRY_OPTOUT="1", DOTNET_NOLOGO="1")
env["PATH"] = str(args.dotnet.parent) + os.pathsep + env["PATH"]
commands = []

def run(label, command, cwd=fixture):
    command = list(map(str, command))
    commands.append({"label": label, "command": command, "cwd": str(cwd)})
    print(label, flush=True)
    with (out / f"{label}.log").open("w") as log:
        result = subprocess.run(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
    if result.returncode:
        print((out / f"{label}.log").read_text()[-12000:], flush=True)
        raise SystemExit(result.returncode)
    return (out / f"{label}.log").read_text()

version = ET.parse(root / "packaging/DotKt.Versions.props").getroot().findtext("PropertyGroup/DotKtKotlinVersion")
local = ET.Element("Project")
properties = ET.SubElement(local, "PropertyGroup")
for key, value in {"KotlinClrRoot": root, "DotKtDll2Klib": here / "build/dll2klib/dll2klib.dll",
                   "DotKtDll2KlibJobs": "4", "DotKtKotlinVersion": version}.items():
    ET.SubElement(properties, key).text = str(value)
ET.indent(local)
(fixture / "DotKt.Local.props").write_text(ET.tostring(local, encoding="unicode") + "\n")
solution = fixture / "DotKt-AspNetCore.sln"
if not solution.exists():
    run("create-solution", [args.dotnet, "new", "sln", "--format", "sln", "--name", "DotKt-AspNetCore"])
    run("add-project", [args.dotnet, "sln", solution, "add", project])

run("restore", [args.dotnet, "restore", project, "--nologo"])
text = run("resolve-public-klibs", [args.dotnet, "msbuild", project,
    "-t:DotKtResolveKlibReferences", "-getItem:DotKtResolvedKlibReference,KotlinCompile,ReferencePath",
    "-getProperty:TargetFramework,RuntimeIdentifier,DotKtStdlib,DotKtKotlinVersion"])
resolved = json.loads(text)
props = resolved["Properties"]
references = resolved["Items"]["DotKtResolvedKlibReference"]
sources = [Path(item["FullPath"]) for item in resolved["Items"]["KotlinCompile"]]
stdlib = Path(props["DotKtStdlib"])
snapshot = {
    "expectedDotktCommit": "400ab50472c0839a9b7de6a94a18db4bc9ad4bb5",
    "dotktSource": str(root), "projectFile": str(project),
    "targetFramework": props["TargetFramework"], "runtimeIdentifier": props["RuntimeIdentifier"],
    "kotlinVersion": props["DotKtKotlinVersion"], "sourceRoot": str(project.parent),
    "sourceFiles": list(map(str, sources)), "stdlib": str(stdlib),
    "expectedCallCount": 6,
    "demo": {"name": "DotKt-AspNetCore", "completionAnchor": "app.Run(", "completionPrefix": "app.",
             "completionNames": ["Run", "StartAsync", "Urls"],
             "focusFile": "HelloController.kt",
             "notification": "HelloController.kt 包含 Kotlin Controller；Program.kt 已注册 MVC，Routes.kt 保留 Minimal API 端点。"},
    "references": [{"sourceAssembly": item["SourceAssembly"], "klib": item["Identity"],
                    "targetFramework": item["TargetFramework"], "runtimeIdentifier": item["RuntimeIdentifier"],
                    "sha256": hashlib.sha256(Path(item["Identity"]).read_bytes()).hexdigest()} for item in references],
    "commands": commands,
}
(out / "input-snapshot.json").write_text(json.dumps(snapshot, indent=2) + "\n")
print(f"Public DotKt target returned {len(references)} KLIBs and {len(sources)} Kotlin files", flush=True)
kotc = root / "toolchain/kotc/build/install/kotc/bin/kotc"
run("frontend-baseline", ["bash", kotc, *sources, "-classpath",
    os.pathsep.join([str(stdlib), *[item["Identity"] for item in references]]), "-d", out / "bir"])
if args.references_only:
    raise SystemExit(0)

# Build the pinned compiler's runtime artifacts only when absent. This mirrors the upstream
# build-stdlib-{ref,rt}.sh commands in Python because macOS's Bash 3 lacks their mapfile builtin.
for tool in ["bir2cir", "ilemit"]:
    dll = root / f"build/{tool}-bin/{tool}.dll"
    if not dll.exists():
        run("build-" + tool, [args.dotnet, "build", root / "toolchain" / tool, "-c", "Release", "-o", dll.parent,
            "--nologo", "-v:q", "-p:UseSharedCompilation=false"], root / "toolchain" / tool)
core_refs = [item["FullPath"] for item in resolved["Items"]["ReferencePath"]
             if item.get("NuGetPackageId") == "Microsoft.NETCore.App.Ref"]
if not core_refs:
    raise SystemExit("MSBuild did not identify the Microsoft.NETCore.App.Ref compile set")
compile_refs = ";".join(core_refs)
reference_dll = root / "build/clr-stdlib/dll/DotKt.Private.Stdlib.dll"
runtime_dll = root / "build/clr-stdlib-rt/dll/DotKt.Stdlib.dll"
if not reference_dll.exists() or not runtime_dll.exists():
    source = root / "libraries/stdlib"
    common = sum([sorted((source / p).rglob("*.kt")) for p in ["common/src", "clr/common", "src", "unsigned/src"]], [])
    clr = [p for p in sorted((source / "clr").rglob("*.kt")) if not p.is_relative_to(source / "clr/common")]
    optins = "kotlin.ExperimentalUnsignedTypes,kotlin.experimental.ExperimentalTypeInference,kotlin.contracts.ExperimentalContracts,kotlin.ExperimentalMultiplatform,kotlin.ExperimentalStdlibApi,kotlin.ExperimentalSubclassOptIn,kotlin.io.encoding.ExperimentalEncodingApi,kotlin.time.ExperimentalTime,kotlin.uuid.ExperimentalUuidApi"
    bir = out / "stdlib-bir"
    run("stdlib-frontend", ["bash", kotc, *common, *clr, "-no-stdlib", "-Xallow-kotlin-package", "-Xexpect-actual-classes",
        "-Xstdlib-compilation", "-Xcontext-parameters", "-Xreturn-value-checker=check", "-XXLanguage:+UnnamedLocalVariables",
        "-Xcommon-sources=" + ",".join(map(str, common)), "-opt-in=" + optins,
        "-Xfragments=common,clr", "-Xfragment-refines=clr:common",
        *["-Xfragment-sources=common:" + str(p) for p in common], *["-Xfragment-sources=clr:" + str(p) for p in clr], "-d", bir], root)
    for mode, assembly, destination in [("metadata", "DotKt.Private.Stdlib", reference_dll), ("runtime", "DotKt.Stdlib", runtime_dll)]:
        if destination.exists(): continue
        cir = out / ("stdlib-" + mode + "-cir")
        refs = compile_refs + ((";" + str(reference_dll)) if mode == "runtime" else "")
        run("stdlib-" + mode + "-lower", [args.dotnet, root / "build/bir2cir-bin/bir2cir.dll", cir,
            "--compile-refs", refs, "--build-stdlib=" + mode, "--stdlib-bindings", source / "clr/stdlib-bindings.json", *sorted(bir.glob("*.bir.json"))])
        run("stdlib-" + mode + "-emit", [args.dotnet, root / "build/ilemit-bin/ilemit.dll", destination.parent, assembly,
            "--compile-refs", compile_refs, "--runtime-refs", "", "--target-framework-moniker", ".NETCoreApp,Version=v10.0",
            "--build-stdlib=" + mode, *sorted(cir.glob("*.cir.json"))])
        if not destination.is_file(): raise SystemExit("Missing emitted stdlib: " + str(destination))

run("build-web", [args.dotnet, "build", project, "--nologo", "-v:minimal", "-p:UseSharedCompilation=false"])
snapshot["commands"] = commands
snapshot["runtimeBuildPassed"] = True
(out / "input-snapshot.json").write_text(json.dumps(snapshot, indent=2) + "\n")
print("Kotlin ASP.NET Core project built successfully", flush=True)
