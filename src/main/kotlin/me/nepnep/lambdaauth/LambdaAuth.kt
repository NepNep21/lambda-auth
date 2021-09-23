package me.nepnep.lambdaauth

import com.lambda.client.plugin.api.Plugin
import com.microsoft.aad.msal4j.PublicClientApplication
import com.microsoft.aad.msal4j.SilentParameters
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

internal object LambdaAuth : Plugin() {

    val logger: Logger = LogManager.getLogger("lambda-auth")
    internal val microsoftApp = PublicClientApplication.builder("810b4a0d-7663-4e28-8680-24458240dee4")
        .setTokenCacheAccessAspect(TokenCache())
        .build()

    override fun onLoad() {
        // Needs cleanup, transfer to function
        microsoftApp.accounts.thenAccept { accounts ->
            try {
                val account = accounts.first { it.username() == AuthModule.msaUsername }
                val parameters = SilentParameters.builder(AuthCommand.scope, account).tenant(AuthCommand.tenant).build()
                AuthCommand.authResult = microsoftApp.acquireTokenSilently(parameters).get()
            } catch (e: Exception) {
                logger.debug("No cache for initial load, ignoring", e)
            }
        }
        modules.add(AuthModule)
        commands.add(AuthCommand)
    }
}