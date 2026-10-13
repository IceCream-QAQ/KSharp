package dotkt.web

import Microsoft.AspNetCore.Builder.WebApplication
import Microsoft.AspNetCore.Builder.MapControllers
import Microsoft.Extensions.DependencyInjection.AddControllers

fun main(args: Array<String>) {
    val builder = WebApplication.CreateBuilder(args)
    builder.Services.AddControllers()
    val app = builder.Build()

    app.mapDotKtEndpoints()
    app.MapControllers()
    app.Run("http://127.0.0.1:5088")
}
