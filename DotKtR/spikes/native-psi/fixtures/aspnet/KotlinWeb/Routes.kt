package dotkt.web

import Microsoft.AspNetCore.Builder.WebApplication
import Microsoft.AspNetCore.Builder.MapGet
import Microsoft.AspNetCore.Http.RequestDelegate
import Microsoft.AspNetCore.Http.WriteAsync

fun WebApplication.mapDotKtEndpoints() {
    MapGet("/", RequestDelegate { context ->
        context.Response.ContentType = "text/plain; charset=utf-8"
        context.Response.WriteAsync("Hello from Kotlin on ASP.NET Core!")
    })

    MapGet("/health", RequestDelegate { context ->
        context.Response.StatusCode = 200
        context.Response.ContentType = "application/json"
        context.Response.WriteAsync("""{"status":"ok","language":"Kotlin","platform":"ASP.NET Core"}""")
    })
}
