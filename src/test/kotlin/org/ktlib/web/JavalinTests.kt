package org.ktlib.web

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class JavalinTests : StringSpec({
    "routing" {
        WebServer.test() { _, client ->
            client.get("/1").body?.string() shouldBe "From1"
        }
    }

    "autoRouting" {
        WebServer.test() { _, client ->
            client.get("/2").body?.string() shouldBe "From2"
        }
    }
})