package me.nepnep.lambdaauth

import com.lambda.client.command.ClientCommand
import com.lambda.client.util.text.MessageSendHelper
import com.microsoft.aad.msal4j.DeviceCodeFlowParameters
import com.microsoft.aad.msal4j.IAuthenticationResult
import com.microsoft.aad.msal4j.MsalException
import com.microsoft.aad.msal4j.SilentParameters

object AuthCommand : ClientCommand(
    name = "lambda-auth",
    description = "Authentication for microsoft accounts with lambda-auth"
) {
    internal val scope = setOf("XboxLive.signin")
    internal const val tenant = "consumers"
    internal var authResult: IAuthenticationResult? = null
    init {
        execute {
            // See https://github.com/AzureAD/microsoft-authentication-library-for-java/blob/855a1aa0631cdd932a8200b89a57efcf8bd9f587/src/samples/public-client/DeviceCodeFlow.java
            LambdaAuth.microsoftApp.accounts.thenAccept { accounts ->
                try {
                    val account = accounts.first { it.username() == AuthModule.msaUsername }

                    try {
                        val parameters = SilentParameters.builder(scope, account).tenant(tenant).build()
                        authResult = LambdaAuth.microsoftApp.acquireTokenSilently(parameters).get()
                        MessageSendHelper.sendChatMessage("Signed in")
                    } catch (e: MsalException) {
                        authResult = makeRequest(e)
                        MessageSendHelper.sendChatMessage("Signed in")
                    }
                } catch (e: NoSuchElementException) {
                    authResult = makeRequest(e)
                    MessageSendHelper.sendChatMessage("Signed in")
                }
            }
        }
    }

    private fun makeRequest(e: Exception): IAuthenticationResult? {
        LambdaAuth.logger.debug("Couldn't load account from cache, starting device flow", e)
        val parameters = DeviceCodeFlowParameters.builder(scope) {
            MessageSendHelper.sendChatMessage(it.message())
        }.tenant(tenant).build()
        return try {
            LambdaAuth.microsoftApp.acquireToken(parameters).get()
        } catch (e: Exception) {
            MessageSendHelper.sendChatMessage("Failed to authenticate with microsoft through device flow")
            LambdaAuth.logger.error("Failed to authenticate with microsoft through device flow", e)
            null
        }
    }
}