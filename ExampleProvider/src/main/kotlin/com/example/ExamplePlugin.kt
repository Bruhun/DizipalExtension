package com.example

import android.content.Context
import com.example.plugin.DizipalProvider
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ExamplePlugin : Plugin() {
    override fun load(context: Context) {
        // Ana sağlayıcıyı sisteme kaydet
        registerMainAPI(DizipalProvider())
    }
}
