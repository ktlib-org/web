package org.ktlib.web

import io.javalin.Javalin
import io.javalin.config.JavalinConfig
import io.javalin.http.Cookie
import io.javalin.http.HttpStatus
import io.javalin.json.JavalinJackson
import io.javalin.openapi.BearerAuth
import io.javalin.openapi.OpenApiInfo
import io.javalin.openapi.plugin.DefinitionConfiguration
import io.javalin.openapi.plugin.OpenApiPlugin
import io.javalin.openapi.plugin.SecurityComponentConfiguration
import io.javalin.openapi.plugin.swagger.SwaggerPlugin
import io.javalin.testtools.JavalinTest
import io.javalin.testtools.TestCase
import org.ktlib.*
import org.ktlib.entities.NotFoundException
import org.ktlib.entities.UnauthorizedException
import org.ktlib.entities.ValidationException
import org.ktlib.error.ErrorReporter
import org.ktlib.trace.Trace
import java.util.function.Consumer


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
abstract class Javalin(private val setup: Consumer<JavalinConfig>) {
    private val useOpenApi = config("web.openApi", true)
    private val allowOpenApiInProd = config("web.allowOpenApiInProd", false)
    private val traceExtraBuilder = config<WebTraceExtraBuilder>("web.traceExtraBuilder", EmptyWebTraceExtraBuilder)
    private val corsOrigins = configOrNull<String>("web.corsOrigins")
    private val port = config("web.serverPort", 8080)
    private var currentApp: Javalin? = null

    val app: Javalin
        get() {
            if (currentApp == null) currentApp = create()
            return currentApp!!
        }


    private fun create(): Javalin = Javalin.create { config ->
        if (corsOrigins != null) {
            config.bundledPlugins.enableCors { cors ->
                cors.addRule { corsConfig ->
                    val origins = corsOrigins.split(",")
                    when {
                        corsOrigins == "*" -> corsConfig.anyHost()
                        origins.size == 1 -> corsConfig.allowHost(origins.first())
                        else -> corsConfig.allowHost(origins.first(), *origins.drop(1).toTypedArray())
                    }
                }
            }
        }

        config.jsonMapper(JavalinJackson(Json.camelCaseMapper))

        if (useOpenApi && (allowOpenApiInProd || Environment.isNotProd)) {
            config.registerPlugin(OpenApiPlugin { openApiConfig ->
                openApiConfig.withDefinitionConfiguration { _: String, definition: DefinitionConfiguration ->
                    definition
                        .withInfo { openApiInfo: OpenApiInfo ->
                            openApiInfo.title = Application.name
                            openApiInfo.version = Environment.version
                        }
                        .withSecurity(SecurityComponentConfiguration().withSecurityScheme("BearerAuth", BearerAuth()))
                }
            })

            config.registerPlugin(SwaggerPlugin { config ->
                config.uiPath = "/"
            })
        }

        setup.accept(config)
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
            var sessionId = context.cookie("ktlibSessionId")?.toUUID()
            if (sessionId == null) {
                sessionId = newUUID4()
                val myCookie = Cookie("ktlibSessionId", sessionId.toString())
                myCookie.isHttpOnly = true
                myCookie.secure = Environment.isNotLocal
                context.cookie(myCookie)
            }
            Trace.sessionId(sessionId)
            Trace.start("Web", context.path(), mapOf("url" to context.path()))
        }

        after { context ->
            Trace.finish(
                context.endpointHandlerPath(),
                traceExtraBuilder.build(context)
            )
        }
    }

    fun start() {
        app.start(port)
    }

    fun test(testCase: TestCase) = JavalinTest.test(create(), testCase = testCase)
}
