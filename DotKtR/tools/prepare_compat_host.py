#!/usr/bin/env python3
"""Prepare the pinned Kotlin/Java host in a sandbox; never modifies the installed IDEs."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import zipfile


def prepare(idea: Path, rider: Path, plugins: Path):
    idea, rider, plugins = idea.resolve(), rider.resolve(), plugins.resolve()
    for installation in (idea, rider):
        if plugins == installation or plugins.is_relative_to(installation):
            raise ValueError("Use a sandbox plugins directory, not an installed IDE directory")
    for directory, expected in [(idea, "262.10968.63"), (rider, "262.10968.170")]:
        info = json.loads((directory / "Resources/product-info.json" if (directory / "Resources/product-info.json").exists() else directory / "product-info.json").read_text())
        if info["buildNumber"] != expected:
            raise ValueError(f"Unsupported SDK {info['buildNumber']}; expected {expected}")
    plugins.mkdir(parents=True, exist_ok=True)
    for name in ("Kotlin", "java"):
        target = plugins / name
        if target.is_symlink():
            raise ValueError(f"Refusing to modify a linked plugin: {target}")
        shutil.copytree(idea / "plugins" / name, target, dirs_exist_ok=True)
    metadata = {"ideaBuild": "262.10968.63", "riderBuild": "262.10968.170", "markers": {}, "removedActions": []}
    with zipfile.ZipFile(idea / "lib/app-backend.jar") as source:
        with zipfile.ZipFile(plugins / "java/lib/dotktr-platform-api.jar", "w") as target:
            for entry in ("com/intellij/psi/jsp/JspxLanguage.class", "com/intellij/psi/jsp/JspLanguage.class"):
                data = source.read(entry)
                target.writestr(entry, data)
                metadata["markers"][entry] = hashlib.sha256(data).hexdigest()
            target.writestr("META-INF/dotktr-origin.txt", "Unmodified JetBrains marker interfaces from the local IDEA SDK. See host-compatibility.json.\n")
    for relative, descriptor in [("java-impl.jar", "META-INF/plugin.xml"), ("modules/intellij.java.backend.jar", "META-INF/JavaActions.xml")]:
        destination = plugins / "java/lib" / relative
        temporary = destination.with_suffix(".tmp")
        with zipfile.ZipFile(idea / "plugins/java/lib" / relative) as source, zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as target:
            for item in source.infolist():
                data = source.read(item.filename)
                if item.filename == descriptor:
                    data, count = re.subn(rb'<action\s+id="CompileFile"\s+class="com.intellij.compiler.actions.CompileFileAction"[^>]*/>', b"", data)
                    if count != 1:
                        raise ValueError(f"Unexpected Java SDK descriptor: {relative}")
                    metadata["removedActions"].append({"jar": relative, "descriptor": descriptor, "id": "CompileFile"})
                target.writestr(item, data)
        temporary.replace(destination)
    bridge = plugins / "dotktr-host-bridge/lib"
    bridge.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(bridge / "bridge.jar", "w") as archive:
        archive.writestr("META-INF/plugin.xml", """<idea-plugin><id>dev.dotktr.hostbridge</id><name>DotKtR Host Bridge</name>
<version>0.1.0-dev</version><vendor>DotKtR contributors</vendor>
<idea-version since-build="262.10968" until-build="262.10968.*"/>
<depends>com.intellij.modules.rider</depends><module value="com.intellij.modules.java-capable"/>
<description>Compatibility capability for the pinned local DotKtR preview host.</description></idea-plugin>""")
    (plugins.parent / "host-compatibility.json").write_text(json.dumps(metadata, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--idea", type=Path, required=True)
    parser.add_argument("--rider", type=Path, required=True)
    parser.add_argument("--plugins", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.idea, args.rider, args.plugins)
