using System.Reflection;
using System.Runtime.InteropServices;

var host = args[0];
var files = Directory.GetFiles(host, "*.dll")
    .Concat(Directory.GetFiles(RuntimeEnvironment.GetRuntimeDirectory(), "*.dll"));
using var context = new MetadataLoadContext(new PathAssemblyResolver(files), "System.Private.CoreLib");
foreach (var assemblyFile in Directory.GetFiles(host, "JetBrains*.dll"))
{
    Assembly assembly;
    try { assembly = context.LoadFromAssemblyPath(assemblyFile); }
    catch (BadImageFormatException) { continue; }
    foreach (var name in args.Skip(1))
    {
        if (name.StartsWith("type:"))
        {
            foreach (var candidate in assembly.GetTypes().Where(t => t.FullName?.Contains(name[5..]) == true))
                Console.WriteLine($"{assembly.GetName().Name}: {candidate.FullName}");
            continue;
        }
        if (name.StartsWith("method:"))
        {
            foreach (var candidate in assembly.GetTypes().Where(t => t.IsAbstract && t.IsSealed))
            foreach (var method in candidate.GetMethods(BindingFlags.Public | BindingFlags.Static | BindingFlags.DeclaredOnly))
                if (method.Name == name[7..]) Console.WriteLine($"{assembly.GetName().Name}: {candidate.FullName}.{method}");
            continue;
        }
        Type? type = assembly.GetType(name);
        if (type is null) continue;
        Console.WriteLine($"{assembly.GetName().Name}: {type}");
        foreach (var member in type.GetMembers(BindingFlags.Public | BindingFlags.Instance | BindingFlags.Static | BindingFlags.DeclaredOnly))
            Console.WriteLine("  " + member);
    }
}
