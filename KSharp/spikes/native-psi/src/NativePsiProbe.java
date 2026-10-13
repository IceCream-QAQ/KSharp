package dotktr.probe;

import com.google.gson.GsonBuilder;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.util.PsiTreeUtil;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.kotlin.psi.KtFile;
import com.jetbrains.rider.projectView.SolutionManager;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;

/** Runs inside the real Rider application, with its own config/system/plugin directories. */
public final class NativePsiProbe implements ApplicationStarter {
    @Override public boolean isHeadless() { return true; }
    @Override public int getRequiredModality() { return NOT_IN_EDT; }

    @Override public void main(List<String> args) {
        Map<String, Object> report = new LinkedHashMap<>();
        boolean success = false;
        try {
            report.put("ide", ApplicationInfo.getInstance().getFullApplicationName());
            report.put("build", ApplicationInfo.getInstance().getBuild().asString());
            report.put("processId", ProcessHandle.current().pid());
            Path root = Path.of(System.getProperty("dotktr.probe.root"));
            Project[] project = new Project[1];
            String solutionPath = System.getProperty("dotktr.probe.solution");
            if (solutionPath != null) {
                var solution = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(solutionPath));
                if (solution == null) throw new IllegalStateException("Solution missing: " + solutionPath);
                project[0] = BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (scope, continuation) ->
                    SolutionManager.INSTANCE.openExistingSolutionOrProject(null, true, solution, false, continuation));
                if (project[0] == null) throw new IllegalStateException("Rider did not open solution");
            } else {
                ApplicationManager.getApplication().invokeAndWait(() -> project[0] =
                    ProjectManager.getInstance().getDefaultProject());
            }
            report.put("projectClass", project[0].getClass().getName());
            var virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("Probe.kt"));
            if (virtualFile == null) throw new IllegalStateException("Probe.kt not found in Rider VFS");
            ApplicationManager.getApplication().runReadAction((Computable<Void>) () -> {
                PsiFile psi = PsiManager.getInstance(project[0]).findFile(virtualFile);
                if (psi == null) throw new IllegalStateException("Rider PsiManager returned null");
                report.put("fileType", virtualFile.getFileType().getName());
                report.put("psiClass", psi.getClass().getName());
                report.put("physical", psi.isPhysical());
                report.put("hostPsi", psi instanceof com.intellij.psi.PsiFile);
                if (!(psi instanceof KtFile kt)) throw new IllegalStateException("Expected native KtFile, got " + psi.getClass());
                kt.getNode().getChildren(null);
                report.put("declarations", kt.getDeclarations().stream().map(d -> d.getClass().getSimpleName()).toList());
                var errors = PsiTreeUtil.findChildrenOfType(kt, PsiErrorElement.class);
                report.put("syntaxErrors", errors.stream().map(PsiErrorElement::getErrorDescription).toList());
                if (!errors.isEmpty()) throw new IllegalStateException("Unexpected syntax errors");
                if (kt.getDeclarations().size() != 2) throw new IllegalStateException("Expected a class and a function");
                return null;
            });
            String analysisSource = System.getProperty("dotktr.probe.analysis.source");
            if (analysisSource != null) {
                if (Boolean.getBoolean("dotktr.probe.backend")) {
                    report.put("backendReady", Class.forName("dotktr.probe.NavigationProbeClient")
                        .getMethod("waitReady", Project.class).invoke(null, project[0]));
                }
                var method = Class.forName("dotktr.probe.K2Probe").getMethod("run", Project.class, String.class, String.class, String.class);
                Map<?, ?> analysis = (Map<?, ?>) method.invoke(null, project[0], analysisSource,
                    System.getProperty("dotktr.probe.analysis.klibs"), System.getProperty("dotktr.probe.analysis.stdlib"));
                report.put("k2", analysis);
                if (!Boolean.TRUE.equals(analysis.get("semanticSuccess"))) throw new IllegalStateException("K2 semantic assertions failed; inspect k2 results");
                if (Boolean.getBoolean("dotktr.probe.completion")) {
                    Map<?, ?> completion = (Map<?, ?>) Class.forName("dotktr.probe.CompletionProbe")
                        .getMethod("run", Project.class, String.class).invoke(null, project[0], analysisSource);
                    report.put("completion", completion);
                    if (!Boolean.TRUE.equals(completion.get("success"))) throw new IllegalStateException("Expected CLR completion variants missing");
                }
                if (Boolean.getBoolean("dotktr.probe.backend")) {
                    Map<?, ?> navigation = (Map<?, ?>) Class.forName("dotktr.probe.NavigationProbeClient")
                        .getMethod("run", Project.class, Map.class).invoke(null, project[0], analysis);
                    report.put("navigation", navigation);
                    if (!Boolean.TRUE.equals(navigation.get("success"))) throw new IllegalStateException("ReSharper target lookup failed");
                }
                if (Boolean.getBoolean("dotktr.probe.edits")) {
                    Map<?, ?> edits = (Map<?, ?>) Class.forName("dotktr.probe.DocumentEditProbe")
                        .getMethod("run", Project.class, String.class).invoke(null, project[0], analysisSource);
                    report.put("edits", edits);
                    if (!Boolean.TRUE.equals(edits.get("success"))) throw new IllegalStateException("Unsaved edit / invalidation assertions failed");
                }
            }
            success = true;
        } catch (Throwable failure) {
            StringWriter trace = new StringWriter();
            failure.printStackTrace(new PrintWriter(trace));
            report.put("error", trace.toString());
        }
        report.put("success", success);
        try {
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(report);
            Files.writeString(Path.of(System.getProperty("dotktr.probe.report")), json + "\n");
            System.out.println(json);
        } catch (Exception failure) { failure.printStackTrace(); }
        System.exit(success ? 0 : 1);
    }
}
