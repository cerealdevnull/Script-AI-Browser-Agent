package com.cereal.script.aiagent

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * Serves the fixture pages in `src/test/resources/fixtures` on localhost. A test can replace or add a page
 * through [pages] (e.g. flip a stock flag) while the server runs.
 */
class FixtureServer : AutoCloseable {
    val pages = mutableMapOf<String, String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path.removePrefix("/").ifEmpty { "index.html" }
            val body = (pages[path] ?: javaClass.getResource("/fixtures/$path")?.readText())?.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(if (body == null) 404 else 200, body?.size?.toLong() ?: -1)
            exchange.responseBody.use { if (body != null) it.write(body) }
        }
        server.start()
    }

    fun url(path: String = ""): String = "http://127.0.0.1:${server.address.port}/$path"

    override fun close() = server.stop(0)
}
