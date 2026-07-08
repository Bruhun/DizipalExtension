package com.example.plugin

import org.jsoup.nodes.Element

object DizipalUtils {
    val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    val ajaxHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
        "X-Requested-With" to "XMLHttpRequest"
    )

    fun extractImage(img: Element?, mainUrl: String): String? {
        if (img == null) return null
        val src = img.attr("data-src").ifBlank { img.attr("src") }
        return when {
            src.isBlank() -> null
            src.startsWith("http") -> src
            src.startsWith("//") -> "https:$src"
            else -> mainUrl + src
        }
    }
}
