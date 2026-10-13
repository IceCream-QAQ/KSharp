using System.Text.Json;
using JetBrains.Lifetimes;
using JetBrains.Application.Parts;
using JetBrains.Application.Threading;
using JetBrains.Application.DataContext;
using JetBrains.Application.UI.PopupLayout;
using JetBrains.ProjectModel;
using JetBrains.Rd.Impl;
using JetBrains.Rd.Tasks;
using JetBrains.ReSharper.Feature.Services.Protocol;
using JetBrains.ReSharper.Feature.Services.Navigation;
using JetBrains.RdBackend.Common.Features.Services;
using JetBrains.ReSharper.Psi;
using JetBrains.ReSharper.Psi.Caches;
using JetBrains.ReSharper.Psi.Tree;
using JetBrains.ReSharper.Psi.Modules;

namespace DotKtR.Backend;

[SolutionComponent(Instantiation.ContainerAsyncPrimaryThread)]
public sealed class NavigationService
{
    private readonly ISolution _solution;
    private readonly DataContexts _dataContexts;
    private readonly DeclaredElementNavigationService _navigation;
    private readonly Lifetime _lifetime;

    public NavigationService(Lifetime lifetime, ISolution solution, DataContexts dataContexts, DeclaredElementNavigationService navigation)
    {
        _solution = solution;
        _dataContexts = dataContexts;
        _navigation = navigation;
        _lifetime = lifetime;
        var call = solution.GetProtocolSolution().GetOrCreateExtension("dotktr.backend.v1", () =>
            new RdCall<string, string>((ctx, reader) => Serializers.ReadString(ctx, reader)!, Serializers.WriteString,
                (ctx, reader) => Serializers.ReadString(ctx, reader)!, Serializers.WriteString));
        call.Set((string request) => Lookup(request));
    }

    private string Lookup(string json)
    {
        try
        {
            using var request = JsonDocument.Parse(json);
            if (request.RootElement.GetProperty("action").GetString() == "assemblyIdentities")
            {
                var identities = request.RootElement.GetProperty("paths").EnumerateArray()
                    .Select(p => p.GetString()!).Distinct().ToDictionary(p => p,
                        p => System.Reflection.AssemblyName.GetAssemblyName(p).FullName);
                return JsonSerializer.Serialize(new { success = true, identities });
            }
            if (request.RootElement.GetProperty("action").GetString() == "ping")
                return _solution.Locks.UsingReadLock(() => JsonSerializer.Serialize(new {
                    ready = _solution.GetPsiServices().CachesState.IsInitialUpdateFinished.Value,
                    solution = _solution.SolutionFilePath.FullPath,
                    projects = _solution.GetTopLevelProjects().Select(p => p.ProjectFileLocation.FullPath).ToArray()
                }));
            return _solution.Locks.UsingReadLock(() => Resolve(request.RootElement));
        }
        catch (Exception error)
        {
            return JsonSerializer.Serialize(new { success = false, error = error.ToString() });
        }
    }

    private string Resolve(JsonElement request)
    {
        var owner = request.GetProperty("owner").GetString()!;
        var identity = request.GetProperty("xmlDocId").GetString()!;
        var sourceAssembly = request.TryGetProperty("sourceAssembly", out var assemblyPath) && assemblyPath.ValueKind == JsonValueKind.String
            ? assemblyPath.GetString() : null;
        var cachedIdentity = request.TryGetProperty("sourceAssemblyIdentity", out var identityProperty) && identityProperty.ValueKind == JsonValueKind.String
            ? identityProperty.GetString() : null;
        var expectedAssembly = cachedIdentity is not null ? new System.Reflection.AssemblyName(cachedIdentity).Name
            : sourceAssembly is null ? null : System.Reflection.AssemblyName.GetAssemblyName(sourceAssembly).Name;
        var consumerPath = request.TryGetProperty("consumerProject", out var consumerProperty) && consumerProperty.ValueKind == JsonValueKind.String
            ? consumerProperty.GetString() : null;
        var consumers = consumerPath is null ? Array.Empty<IProject>() : _solution.GetTopLevelProjects()
            .Where(p => p.ProjectFileLocation.FullPath == consumerPath).ToArray();
        var modules = consumers.SelectMany(p => p.GetPsiModules()).Where(m => m.IsValid()).ToArray();
        if (consumerPath is not null && modules.Length != 1)
            return JsonSerializer.Serialize(new { success = false, retryable = modules.Length == 0,
                error = "Consumer project must resolve to one .NET module",
                consumerPath, modules = modules.Select(m => new { m.Name, targetFramework = m.TargetFrameworkId.ToString() }).ToArray() });
        // Compiled signatures and external-source retargeting both read the ambient resolve
        // context. A module-specific symbol scope alone does not establish that context.
        using var compilationContext = modules.Length == 1
            ? CompilationContextCookie.OverrideOrCreate(consumers.Single().GetResolveContext(modules[0].TargetFrameworkId))
            : CompilationContextCookie.GetExplicitUniversalContextIfNotSet();
        var scope = modules.Length == 1
            ? _solution.GetPsiServices().Symbols.GetSymbolScope(modules[0], true, true)
            : _solution.GetPsiServices().Symbols.GetSymbolScope(LibrarySymbolScope.NONE, true);
        var allTypes = scope.GetTypeElementsByCLRName(owner).ToArray();
        string? AssemblyName(ITypeElement type)
        {
            var module = ((IClrDeclaredElement)type).Module;
            if (module is IAssemblyPsiModule assembly) return assembly.Assembly.AssemblyName.Name;
            if (module.ContainingProjectModule is IProject project) return project.GetOutputAssemblyName(module.TargetFrameworkId);
            return null;
        }
        var types = allTypes.Where(type => expectedAssembly is null || AssemblyName(type) == expectedAssembly).ToArray();
        var declarationsToCheck = identity.StartsWith("T:", StringComparison.Ordinal)
            ? types.Cast<IDeclaredElement>()
            : types.SelectMany(type => type.GetMembers().Cast<IDeclaredElement>());
        var matches = declarationsToCheck
            .Where(element => element is IXmlDocIdOwner doc && doc.XMLDocId == identity).Distinct().ToArray();
        if (matches.Length != 1)
            return JsonSerializer.Serialize(new { success = false, retryable = allTypes.Length == 0,
                owner, identity, expectedAssembly, consumerPath, count = matches.Length,
                error = "Expected a unique declaration with the exact CLR signature and assembly",
                typeAssemblies = allTypes.Select(AssemblyName).ToArray(),
                candidates = types.SelectMany(t => t.GetMembers()).OfType<IXmlDocIdOwner>().Select(e => e.XMLDocId).ToArray() });
        var target = matches[0];
        var declarations = target.GetDeclarations().Select(declaration =>
        {
            var range = declaration.GetNameDocumentRange();
            return new { file = declaration.GetSourceFile()?.GetLocation().FullPath,
                start = range.TextRange.StartOffset, end = range.TextRange.EndOffset };
        }).ToArray();
        var navigate = request.GetProperty("action").GetString() == "navigate";
        if (navigate)
        {
            var context = _dataContexts.CreateOnApplicationWideState(_lifetime);
            var options = NavigationOptions.FromDataContext(context, "KSharp navigation");
            _navigation.Navigate(target, options.PopupWindowContextSource, true, null, JetBrains.IDE.TabOptions.NormalTab);
        }
        return JsonSerializer.Serialize(new { success = true, owner, identity, expectedAssembly, consumerPath,
            symbol = target.ShortName, implementation = target.GetType().FullName, declarations,
            hasSourceDeclaration = declarations.Length > 0, navigationRequested = navigate });
    }
}
