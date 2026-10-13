package dotkt.web.controllers

import Microsoft.AspNetCore.Mvc.ApiControllerAttribute
import Microsoft.AspNetCore.Mvc.ControllerBase
import Microsoft.AspNetCore.Mvc.FromRouteAttribute
import Microsoft.AspNetCore.Mvc.HttpGetAttribute
import Microsoft.AspNetCore.Mvc.IActionResult
import Microsoft.AspNetCore.Mvc.RouteAttribute

data class GreetingResponse(val message: String, val language: String)

@ApiControllerAttribute
@RouteAttribute("api/hello")
class HelloController : ControllerBase() {
    @HttpGetAttribute
    fun hello(): IActionResult = Ok(GreetingResponse("Hello from a Kotlin Controller!", "Kotlin"))

    @HttpGetAttribute
    @RouteAttribute("{name}")
    fun greet(@FromRouteAttribute name: String): IActionResult =
        Ok(GreetingResponse("Hello, $name!", "Kotlin"))
}
