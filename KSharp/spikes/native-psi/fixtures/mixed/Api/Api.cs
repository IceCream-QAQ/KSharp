namespace DotKtProbe;

public class Base<T>
{
    public static T Echo(T value) => value;
    protected static string ProtectedValue() => "protected";
}

public class Derived : Base<string>;

public class Box<T>(T value)
{
    public T Value { get; set; } = value;
    public T Read() => Value;
}

public static class Api
{
    public static string Pick(int value) => $"int:{value}";
    public static string Pick(string value) => $"string:{value}";
    public static T Identity<T>(T value) => value;
}

public static class Extensions
{
    public static string Describe(this Box<string> box) => box.Value;
}
