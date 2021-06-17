package me.nepnep.lambdaauth

import com.lambda.client.plugin.api.Plugin

internal object LambdaAuth: Plugin() {
    override fun onLoad() {
        modules.add(AuthModule)
    }
}