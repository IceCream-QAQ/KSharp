package probe

import DotKtProbe.Api
import DotKtProbe.Box
import DotKtProbe.Derived
import DotKtProbe.Describe

fun probe(): String {
    val number: String = Api.Pick(42)
    val text: String = Api.Pick("hello")
    val generic: String = Api.Identity("generic")
    val inherited: String = Derived.Echo("inherited")
    val box = Box("boxed")
    val property: String = box.Value
    val method: String = box.Read()
    val extension: String = box.Describe()
    return number + text + generic + inherited + property + method + extension
}
