package org.ktlib.web

import io.javalin.apibuilder.ApiBuilder.get
import org.ktlib.instancesFromFilesRelativeToClass

object WebServer : Javalin({ config ->
    config.router.apiBuilder {
        get("/1") { ctx -> ctx.result("From1") }

        instancesFromFilesRelativeToClass<WebServer, Router>().forEach { it.route() }
    }
})

object MyRoutes : Router {
    override fun route() {
        get("/2") { ctx -> ctx.result("From2") }
    }
}