package org.ktlib.web

import io.javalin.Javalin
import io.javalin.http.HttpStatus
import io.javalin.json.JavalinJackson
import io.javalin.openapi.BearerAuth
import io.javalin.openapi.OpenApiInfo
import io.javalin.openapi.plugin.DefinitionConfiguration
import io.javalin.openapi.plugin.OpenApiPlugin
import io.javalin.openapi.plugin.OpenApiPluginConfiguration
import io.javalin.openapi.plugin.SecurityComponentConfiguration
import io.javalin.openapi.plugin.swagger.SwaggerConfiguration
import io.javalin.openapi.plugin.swagger.SwaggerPlugin
import io.javalin.security.AccessManager
import io.javalin.testtools.JavalinTest
import io.javalin.testtools.TestCase
import org.ktlib.*
import org.ktlib.entities.NotFoundException
import org.ktlib.entities.UnauthorizedException
import org.ktlib.entities.ValidationException
import org.ktlib.error.ErrorReporter
import org.ktlib.trace.Trace


/**
 * This creates a Javalin server. Additional configuration, like adding routes can be added when instantiating this
 * class like this:
 *
 * ```
 * object WebServer : Javalin({
 *     routes {
 *        get("/myPath", myHandler)
 *     }
 * })
 *
 * WebServer.start()
 * ```
 * This class will automatically find instances of the Router interface in the same package at the WebServer class or
 * any package below it. So you may not need any initialization and can configure it like this:
 * ```
 * object WebServer : Javalin()
 * ```
 *
 * The created Javalin instance can be accessed at `WebServer.app`
 *
 * These following configurations are used by this object:
 * - web.openApi if this is `true` the root of the api will serve swagger and /openapi will server the OpenAPI json
 * - web.allowOpenApiInProd if this is `true` when the swagger will be served in production, the default is `false`
 * - web.traceExtraBuilder this is an instance of `WebTraceExtraBuilder` that allows you to add additional info to
 * web traces for each request
 * - web.corsOrigins comma separated list of domains that CORS should be enabled for, you can enter `*` to enable all origins
 * - web.accessManager an instance of `io.javalin.core.security.AccessManager` to be used for the Javalin instance
 * - web.serverPort the port to use for the web server, the default is `8080`
 */
abstract class Javalin(private val setup: Javalin.() -> Any = {}) {
    private val useOpenApi = config("web.openApi", true)
    private val allowOpenApiInProd = config("web.allowOpenApiInProd", false)
    private val traceExtraBuilder = config<WebTraceExtraBuilder>("web.traceExtraBuilder", EmptyWebTraceExtraBuilder)
    private val corsOrigins = configOrNull<String>("web.corsOrigins")
    private val port = config("web.serverPort", 8080)
    private var accessManager: AccessManager? = null
    private var currentApp: Javalin? = null

    constructor(accessManager: AccessManager, setup: Javalin.() -> Any = {}) : this(setup) {
        this.accessManager = accessManager
    }

    val app: Javalin
        get() {
            if (currentApp == null) currentApp = create()
            return currentApp!!
        }


    private fun create(): Javalin = Javalin.create {
        if (corsOrigins != null) {
            it.plugins.enableCors { cors ->
                cors.add { corsConfig ->
                    val origins = corsOrigins.split(",")
                    when {
                        corsOrigins == "*" -> corsConfig.anyHost()
                        origins.size == 1 -> corsConfig.allowHost(origins.first())
                        else -> corsConfig.allowHost(origins.first(), *origins.drop(1).toTypedArray())
                    }
                }
            }
        }

        if (accessManager != null) {
            it.accessManager { handler, ctx, routeRoles ->
                val path = ctx.path()
                if (useOpenApi && (path == "/" || path == "/openapi" || path.startsWith("/webjars/swagger-ui/"))) {
                    if (Environment.isNotProd || allowOpenApiInProd) {
                        handler.handle(ctx)
                    } else {
                        ctx.status(HttpStatus.NOT_FOUND)
                    }
                } else {
                    accessManager?.manage(handler, ctx, routeRoles)
                }
            }
        }

        it.jsonMapper(JavalinJackson(Json.camelCaseMapper))

        if (useOpenApi) {
            val openApiConfig = OpenApiPluginConfiguration()
                .withDefinitionConfiguration { _: String, definition: DefinitionConfiguration ->
                    definition
                        .withOpenApiInfo { openApiInfo: OpenApiInfo ->
                            openApiInfo.title = Application.name
                            openApiInfo.version = Environment.version
                        }
                        .withSecurity(SecurityComponentConfiguration().withSecurityScheme("BearerAuth", BearerAuth()))
                }


            it.plugins.register(OpenApiPlugin(openApiConfig))

            val swaggerConfiguration = SwaggerConfiguration()
            swaggerConfiguration.uiPath = "/"
            it.plugins.register(SwaggerPlugin(swaggerConfiguration))
        }
    }.apply {
        exception(ValidationException::class.java) { e, ctx ->
            ctx.json(e.validationErrors)
            ctx.status(HttpStatus.BAD_REQUEST)
        }

        exception(UnauthorizedException::class.java) { _, ctx ->
            ctx.status(HttpStatus.FORBIDDEN)
        }

        exception(NotFoundException::class.java) { _, ctx ->
            ctx.status(HttpStatus.NOT_FOUND)
        }

        exception(Exception::class.java) { e, ctx ->
            e.printStackTrace()
            ErrorReporter.report(e)
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
        }

        before { context ->
            ErrorReporter.setContext(context.req())
            Trace.clear()
            Trace.start("Web", context.path(), mapOf("url" to context.path()))
        }

        after { context ->
            Trace.finish(
                context.endpointHandlerPath(),
                traceExtraBuilder.build(context)
            )
        }

        setup(this)
    }

    fun start() {
        app.start(port)
    }

    fun test(testCase: TestCase) = JavalinTest.test(create(), testCase = testCase)
}
