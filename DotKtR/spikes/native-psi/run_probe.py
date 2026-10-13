#!/usr/bin/env python3
"""Compile a probe against local SDKs and run it in an isolated real Rider process."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import tempfile
import zipfile
import hashlib
import re

parser = argparse.ArgumentParser()
parser.add_argument("--rider", type=Path, default=Path.home() / "Applications/Rider.app/Contents")
parser.add_argument("--idea", type=Path, default=Path.home() / "Applications/IntelliJ IDEA.app/Contents")
parser.add_argument("--jdk", type=Path)
parser.add_argument("--dotnet", type=Path, default=Path.home() / ".dotnet/dotnet")
parser.add_argument("--with-java-psi", action="store_true")
parser.add_argument("--full-kotlin", action="store_true", help="Load IDEA Java and Kotlin plugins with recorded Rider compatibility adaptations")
parser.add_argument("--solution", type=Path)
parser.add_argument("--experimental-java-host", action="store_true", help="Probe Java-capable product compatibility with the real Java plugin present")
parser.add_argument("--analysis-source", type=Path)
parser.add_argument("--clr-sessions", action="store_true")
parser.add_argument("--completion", action="store_true")
parser.add_argument("--backend", action="store_true")
parser.add_argument("--navigate", action="store_true")
parser.add_argument("--edits", action="store_true")
parser.add_argument("--ui", action="store_true", help="Open an interactive Rider window and import the demo instead of running the headless assertions")
parser.add_argument("--ui-checks", action="store_true", help="Exercise actual editor completion and daemon diagnostics in a fresh fixture, then restore it")
parser.add_argument("--navigation-checks", action="store_true", help="Exercise normal declaration navigation in the ASP.NET Core or mixed fixture")
parser.add_argument("--run-checks", action="store_true", help="Exercise native .NET project Run from the Kotlin main context in a fresh ASP.NET Core fixture")
parser.add_argument("--fresh", action="store_true", help="Use fresh IDE state and a copy of the built-in mixed fixture without .idea/bin/obj")
parser.add_argument("--sandbox-dir", type=Path, help="Reuse an existing interactive sandbox, including its settings and edited fixture")
parser.add_argument("--dotkt-source", type=Path, default=Path("/private/tmp/dotktr-dotkt-400ab504"))
parser.add_argument("--input-snapshot", type=Path)
parser.add_argument("--fixture-root", type=Path, help="Project tree to copy into a GUI/fresh sandbox; defaults to fixtures/mixed")
parser.add_argument("--license-config", type=Path, help="Existing local Rider configuration used for normal license validation")
args = parser.parse_args()
if args.navigate and not args.backend: parser.error("--navigate requires --backend")
if (args.backend or args.completion or args.edits or args.clr_sessions) and not args.analysis_source:
    parser.error("Semantic, completion, backend, and edit probes require --analysis-source")
if args.analysis_source and (not args.full_kotlin or not args.solution):
    parser.error("--analysis-source requires --full-kotlin and --solution")
if args.ui and not (args.analysis_source and args.clr_sessions and args.backend):
    parser.error("--ui requires --analysis-source, --clr-sessions and --backend")
if args.ui and (args.edits or args.navigate):
    parser.error("--ui does not run automated edit or navigation probes")
if args.sandbox_dir and (not args.ui or args.fresh):
    parser.error("--sandbox-dir requires --ui and cannot be combined with --fresh")
if args.ui_checks and not (args.ui and args.fresh):
    parser.error("--ui-checks requires --ui --fresh to avoid editing an existing user session")
if args.navigation_checks and not args.ui:
    parser.error("--navigation-checks requires --ui")
if args.run_checks and not (args.ui and args.fresh):
    parser.error("--run-checks requires --ui --fresh")
args.dotkt_source = args.dotkt_source.resolve()
if args.jdk is None:
    args.jdk = Path(subprocess.check_output(["/usr/libexec/java_home", "-v", "25"], text=True).strip())
here = Path(__file__).resolve().parent
if args.input_snapshot is None: args.input_snapshot = here / "build/input-snapshot.json"
if args.analysis_source and not args.input_snapshot.is_file():
    parser.error("Run prepare_inputs.py before an analysis probe, or supply --input-snapshot")
snapshot = json.loads(args.input_snapshot.read_text()) if args.analysis_source else {}
demo = snapshot.get("demo", {})
if args.ui_checks and demo:
    parser.error("--ui-checks currently exercises the mixed fixture; custom project profiles use their own checks")
variant = "full-kotlin" if args.full_kotlin else ("java-psi" if args.with_java_psi else "compiler-only")
if args.solution: variant += "-solution"
if args.experimental_java_host:
    if not args.full_kotlin: parser.error("--experimental-java-host requires --full-kotlin")
    variant += "-java-host"
if args.ui: variant += "-ui"
out = args.sandbox_dir.resolve() if args.sandbox_dir else here / "build" / variant
if args.fresh:
    (here / "build").mkdir(exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix=variant + "-fresh-", dir=here / "build"))
if args.fresh or args.ui:
    fixture = args.fixture_root.resolve() if args.fixture_root else here / "fixtures/mixed"
    if not (out / "fixture").exists():
        shutil.copytree(fixture, out / "fixture", ignore=shutil.ignore_patterns(".idea", "bin", "obj", "*.iml", "*.user"))
    if args.solution: args.solution = out / "fixture" / args.solution.resolve().relative_to(fixture)
    if args.analysis_source: args.analysis_source = out / "fixture" / args.analysis_source.resolve().relative_to(fixture)
    if args.ui:
        demo_name = re.sub(r"[^A-Za-z0-9_.-]", "-", demo.get("name", "DotKt-Demo"))
        named_solution = out / "fixture" / ("DotKt-UI-Checks.sln" if args.ui_checks else demo_name + ".sln")
        if not named_solution.exists(): shutil.copy2(args.solution, named_solution)
        args.solution = named_solution
classes = out / "classes"
plugin = out / "plugins" / "dotktr-native-psi" / "lib"
for path in [classes, plugin, out / "project", out / "config", out / "system", out / "log"]:
    path.mkdir(parents=True, exist_ok=True)
if (out / "report.json").exists():
    history = out / "history" / str(time.time_ns())
    history.mkdir(parents=True)
    for name in ["report.json", "console.log", "launch.json"]:
        if (out / name).exists(): shutil.copy2(out / name, history / name)
    (out / "report.json").unlink()
if args.license_config:
    license_path = args.license_config / "rider.key"
    target = out / "config/rider.key"
    if not target.exists(): target.symlink_to(license_path)
kotlin = args.idea / "plugins/Kotlin/lib/kotlinc.kotlin-compiler-common.jar"
java_psi = args.idea / "plugins/java/lib/intellij.java.psi.jar"
cp = os.pathsep.join(map(str, [*sorted((args.rider / "lib").glob("*.jar")), kotlin, java_psi]))
compiled = subprocess.run([str(args.jdk / "bin/javac"), "--release", "25", "-cp", cp,
                          "-d", str(classes), str(here / "src/NativePsiProbe.java")])
if compiled.returncode: raise SystemExit(compiled.returncode)
if args.analysis_source:
    if not args.full_kotlin: parser.error("--analysis-source requires --full-kotlin")
    compiler_libs = args.dotkt_source / "toolchain/kotc/build/install/kotc/lib"
    compiler_cp = os.pathsep.join(map(str, sorted(compiler_libs.glob("*.jar"))))
    sdk_cp = cp + os.pathsep + os.pathsep.join(map(str, (args.idea / "plugins/Kotlin/lib").glob("*.jar")))
    sources = [here / "src" / name for name in ["K2Probe.kt", "CompletionProbe.kt", "NavigationProbeClient.kt", "DocumentEditProbe.kt", "DotKtModuleBinding.kt"]]
    if args.ui: sources += [here / "src" / name for name in ["GuiDemoActivity.kt", "GuiEditorChecks.kt", "ClrNavigation.kt", "DotNetNavigationService.kt", "DotNetNavigationChecks.kt", "DotKtRunConfiguration.kt", "MainRunChecks.kt"]]
    if args.clr_sessions:
        sources.append(here / "src/ClrSessionConfigurator.kt")
        dotkt_kotlin = args.dotkt_source / "toolchain/kotc/src/main/kotlin/kotc"
        sources += [dotkt_kotlin / "pipeline" / name for name in [
            "ClrStaticScopes.kt", "ClrStaticVisibility.kt", "ClrStaticConflictResolver.kt", "ClrDefaultImports.kt"]]
        sources.append(dotkt_kotlin / "frontend/StaticOwners.kt")
    compiled = subprocess.run([str(args.jdk / "bin/java"), "-cp", compiler_cp,
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "21",
        "-Xcontext-parameters", "-cp", sdk_cp, "-d", str(classes), *map(str, sources)])
    if compiled.returncode: raise SystemExit(compiled.returncode)
with zipfile.ZipFile(plugin / "probe.jar", "w", zipfile.ZIP_DEFLATED) as jar:
    for root in [classes, here / "resources"]:
        for file in root.rglob("*"):
            if not file.is_file(): continue
            if args.full_kotlin and file.name == "plugin.xml":
                import xml.etree.ElementTree as ET
                xml = ET.parse(file).getroot()
                ET.SubElement(xml, "depends").text = "org.jetbrains.kotlin"
                extensions = xml.find("extensions")
                for entry in list(extensions):
                    if entry.tag != "appStarter": extensions.remove(entry)
                if args.clr_sessions:
                    ext = ET.SubElement(xml, "extensions", {"defaultExtensionNs": "org.jetbrains.kotlin"})
                    ET.SubElement(ext, "llFirSessionConfigurator", {"implementation": "dotktr.probe.ClrSessionConfigurator"})
                if args.analysis_source:
                    ext = ET.SubElement(xml, "extensions", {"defaultExtensionNs": "org.jetbrains.kotlin"})
                    ET.SubElement(ext, "k2KaModuleFactory", {"implementation": "dotktr.probe.DotKtModuleFactory"})
                    ET.SubElement(ext, "kotlinContentScopeRefiner", {"implementation": "dotktr.probe.DotKtContentScopeRefiner"})
                    ET.SubElement(extensions, "projectService", {
                        "serviceInterface": "org.jetbrains.kotlin.idea.base.projectStructure.RootKindMatcher",
                        "serviceImplementation": "dotktr.probe.DotKtRootKindMatcher", "overrides": "true"})
                if args.ui:
                    ET.SubElement(extensions, "postStartupActivity", {"implementation": "dotktr.probe.GuiDemoActivity"})
                    ET.SubElement(extensions, "notificationGroup", {"id": "DotKt Demo", "displayType": "BALLOON"})
                    ET.SubElement(extensions, "lang.directNavigationProvider", {"implementation": "dotktr.probe.DotNetDirectNavigationProvider", "order": "first"})
                    ET.SubElement(extensions, "runConfigurationProducer", {"implementation": "dotktr.probe.DotKtRunConfigurationProducer"})
                    ET.SubElement(extensions, "runLineMarkerContributor", {"language": "kotlin", "implementationClass": "dotktr.probe.DotKtRunLineMarkerContributor"})
                    run_ext = ET.SubElement(xml, "extensions", {"defaultExtensionNs": "org.jetbrains.kotlin.idea.codeInsight.lineMarkers"})
                    ET.SubElement(run_ext, "kotlinRunLineMarkerHider", {"implementation": "dotktr.probe.DotKtJvmRunMarkerHider"})
                    actions = ET.SubElement(xml, "actions")
                    action = ET.SubElement(actions, "action", {"id": "DotKt.ShowDemo", "class": "dotktr.probe.ShowDemoAction", "text": "DotKt: Show Demo Completion"})
                    ET.SubElement(action, "add-to-group", {"group-id": "ToolsMenu", "anchor": "last"})
                jar.writestr("META-INF/plugin.xml", ET.tostring(xml))
            else: jar.write(file, str(file.relative_to(root)))
if args.full_kotlin:
    for name in ["Kotlin", "java"]:
        shutil.copytree(args.idea / "plugins" / name, out / "plugins" / name, dirs_exist_ok=True)
    sdk_adaptations = {"sourceIdea": str(args.idea), "markerInterfaces": {}, "removedActions": []}
    # JavaTreeCopyHandler references these platform marker interfaces even for Kotlin PSI copies.
    # IDEA ships them in app-backend.jar; Rider doesn't. Keep the original SDK bytes and provenance.
    missing_api = ["com/intellij/psi/jsp/JspxLanguage.class", "com/intellij/psi/jsp/JspLanguage.class"]
    with zipfile.ZipFile(args.idea / "lib/app-backend.jar") as source_jar:
        with zipfile.ZipFile(out / "plugins/java/lib/dotktr-platform-api.jar", "w") as compatibility_jar:
            for entry in missing_api:
                data = source_jar.read(entry)
                compatibility_jar.writestr(entry, data)
                sdk_adaptations["markerInterfaces"][entry] = hashlib.sha256(data).hexdigest()
            compatibility_jar.writestr("META-INF/dotktr-origin.txt", "Original, unmodified marker interfaces from " + str(args.idea / "lib/app-backend.jar") + "\n")
    # Preserve Rider's CompileFile action. The copied Java plugin's registration conflicts with it.
    for relative, descriptor in [("java-impl.jar", "META-INF/plugin.xml"),
                                 ("modules/intellij.java.backend.jar", "META-INF/JavaActions.xml")]:
        original_jar = args.idea / "plugins/java/lib" / relative
        adapted_jar = out / "plugins/java/lib" / relative
        with zipfile.ZipFile(original_jar) as source_jar, zipfile.ZipFile(adapted_jar, "w", zipfile.ZIP_DEFLATED) as target_jar:
            for item in source_jar.infolist():
                data = source_jar.read(item.filename)
                if item.filename == descriptor:
                    data, count = re.subn(rb'<action\s+id="CompileFile"\s+class="com.intellij.compiler.actions.CompileFileAction"[^>]*/>', b'', data)
                    if count != 1: raise RuntimeError(f"Unexpected Java CompileFile descriptor in {original_jar}")
                    sdk_adaptations["removedActions"].append({"jar": relative, "descriptor": descriptor, "id": "CompileFile"})
                target_jar.writestr(item, data)
    (out / "sdk-adaptations.json").write_text(json.dumps(sdk_adaptations, indent=2) + "\n")
    if args.experimental_java_host:
        capability = out / "plugins/dotktr-java-host/lib"
        capability.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(capability / "host.jar", "w") as jar:
            jar.writestr("META-INF/plugin.xml", '<idea-plugin><id>dotktr.java.host.probe</id><name>DotKtR Java Host Probe</name><version>0.0.1</version><vendor>DotKtR</vendor><depends>com.intellij.modules.platform</depends><module value="com.intellij.modules.java-capable"/></idea-plugin>')
else:
    shutil.copy2(kotlin, plugin / kotlin.name)
    if args.with_java_psi: shutil.copy2(java_psi, plugin / java_psi.name)
shutil.copy2(here / "fixtures/Probe.kt", out / "project/Probe.kt")
if args.backend:
    backend_environment = os.environ.copy()
    backend_environment.setdefault("DOTNET_CLI_HOME", "/tmp/dotktr-dotnet-home")
    backend_environment.setdefault("NUGET_PACKAGES", "/tmp/dotktr-nuget")
    subprocess.run([str(args.dotnet), "build", str(here / "backend/NativeProbe.Backend.csproj"),
                    "--nologo", "-v:q", "-p:UseSharedCompilation=false",
                    f"-p:RiderHost={args.rider / 'lib/ReSharperHost'}"],
                   env=backend_environment, check=True)
    backend_dir = plugin.parent / "dotnet"
    backend_dir.mkdir(parents=True, exist_ok=True)
    for suffix in ["dll", "pdb"]:
        shutil.copy2(here / f"backend/bin/Debug/net8.0/NativeProbe.Backend.{suffix}", backend_dir)
info = json.loads((args.rider / "Resources/product-info.json").read_text())
launch = next(item for item in info["launch"] if item["os"] == "macOS" and item["arch"] == "aarch64")
vmargs = [s.replace("$APP_PACKAGE", str(args.rider.parent)) for s in launch["additionalJvmArguments"]]
if args.solution: vmargs.append(f"-Ddotktr.probe.solution={args.solution.resolve()}")
if args.analysis_source:
    vmargs += [f"-Ddotktr.probe.analysis.source={args.analysis_source.resolve()}",
               f"-Ddotktr.probe.analysis.snapshot={args.input_snapshot.resolve()}",
               f"-Ddotktr.probe.analysis.klibs={here / 'build/klib'}",
               f"-Ddotktr.probe.analysis.stdlib={args.dotkt_source / 'build/clr-stdlib-frontend-klib/kotlin-stdlib-clr-frontend.klib'}"]
    # Kotlin's global source-index filter consults IDEA's Java-root model directly, which Rider
    # doesn't use for .ktproj. Permit source indexing; KaModule ownership/scopes remain snapshot-bound.
    vmargs.append("-Dkotlin.index.non.source.roots=true")
if args.clr_sessions: vmargs.append("-Ddotktr.probe.clr.sessions=true")
if args.completion: vmargs.append("-Ddotktr.probe.completion=true")
if args.backend: vmargs.append("-Ddotktr.probe.backend=true")
if args.navigate: vmargs.append("-Ddotktr.probe.navigate=true")
if args.edits: vmargs.append("-Ddotktr.probe.edits=true")
if args.ui:
    vmargs += ["-Ddotktr.probe.ui=true", "-Dapple.awt.application.name=DotKtR Demo",
               "-Dide.experimental.ui.onboarding=false"]
    if demo:
        vmargs += ["-Ddotktr.probe.completion.anchor=" + demo["completionAnchor"],
                   "-Ddotktr.probe.completion.prefix=" + demo["completionPrefix"],
                   "-Ddotktr.probe.completion.names=" + ",".join(demo["completionNames"]),
                   "-Ddotktr.probe.ui.notification=" + demo["notification"]]
        if demo.get("focusFile"):
            vmargs.append("-Ddotktr.probe.ui.focus.file=" + demo["focusFile"])
if args.ui_checks: vmargs.append("-Ddotktr.probe.ui.checks=true")
if args.navigation_checks: vmargs.append("-Ddotktr.probe.navigation.checks=true")
if args.run_checks: vmargs.append("-Ddotktr.probe.run.checks=true")
cmd = [str(args.rider / "jbr/Contents/Home/bin/java"), "-Xmx1536m", *vmargs,
       f"-Djava.awt.headless={'false' if args.ui else 'true'}", "-Didea.is.internal=true", "-Didea.auto.reload.plugins=false",
       "-Didea.trust.all.projects=true", "-Didea.initially.ask.config=false",
       "-Djb.consents.confirmation.enabled=false",
       f"-Didea.config.path={out / 'config'}", f"-Didea.system.path={out / 'system'}",
       f"-Didea.log.path={out / 'log'}", f"-Didea.plugins.path={out / 'plugins'}",
       f"-Ddotktr.probe.root={out / 'project'}", f"-Ddotktr.probe.report={out / 'report.json'}",
       "-cp", os.pathsep.join(str(args.rider / "lib" / n) for n in launch["bootClassPathJarNames"]),
       launch["mainClass"], *([str(args.solution.resolve())] if args.ui else ["dotktr-probe"])]
(out / "launch.json").write_text(json.dumps(cmd, indent=2) + "\n")
print(f"Running {info['name']} {info['version']}: {variant}", flush=True)
with (out / "console.log").open("w") as log:
    environment = os.environ.copy()
    if args.ui and demo:
        environment["PATH"] = str(Path.home() / ".dotnet") + os.pathsep + environment["PATH"]
        environment["JAVA_HOME"] = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip()
    if args.ui: environment["IDEA_RESTART_VIA_EXIT_CODE"] = "88"
    while True:
        result = subprocess.run(cmd, cwd=args.rider / "bin", env=environment, stdout=log, stderr=subprocess.STDOUT)
        if not args.ui or result.returncode != 88: break
        print("Restarting the same Rider sandbox with its demo plugin and settings", flush=True)
if (out / "report.json").exists():
    report = json.loads((out / "report.json").read_text())
    summary = {key: report[key] for key in ["ide", "build", "success", "psiClass", "completion", "navigation", "edits"] if key in report}
    if "k2" in report:
        summary["k2"] = {key: report["k2"].get(key) for key in ["analysisModule", "klibCount", "semanticSuccess", "diagnostics"]}
    if "error" in report: summary["error"] = report["error"][-9000:]
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print("Full report:", out / "report.json")
else:
    print((out / "console.log").read_text()[-16000:])
raise SystemExit(result.returncode)
