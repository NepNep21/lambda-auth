package me.nepnep.lambdaauth

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.lambda.client.event.events.GuiEvent
import com.lambda.client.mixin.extension.message
import com.lambda.client.module.Category
import com.lambda.client.plugin.api.PluginModule
import com.lambda.event.listener.listener
import com.mojang.authlib.Agent
import com.mojang.authlib.UserType
import com.mojang.authlib.exceptions.AuthenticationException
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService
import com.mojang.util.UUIDTypeAdapter
import net.minecraft.client.gui.GuiDisconnected
import net.minecraft.client.multiplayer.GuiConnecting
import net.minecraft.client.resources.I18n
import net.minecraft.util.Session
import net.minecraftforge.fml.common.ObfuscationReflectionHelper
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.util.EntityUtils
import java.util.*

internal object AuthModule : PluginModule(
    name = "LambdaAuth",
    category = Category.MISC,
    description = "Automatically fixes the invalid session error",
    pluginMain = LambdaAuth
) {
    private val type by setting("Type", Type.MOJANG)

    private val email by setting("Email", "", { type == Type.MOJANG })
    private val password by setting("Password", "", { type == Type.MOJANG })

    internal val msaUsername by setting("Microsoft email", "", { type == Type.MICROSOFT })

    private enum class Type {
        MOJANG,
        MICROSOFT
    }

    private val gson = Gson()
    private val jsonClass = JsonObject::class.java

    init {
        listener<GuiEvent.Displayed> { event ->
            val message = I18n.format("disconnect.loginFailed") + ": " + I18n.format("disconnect.loginFailedInfo.invalidSession")

            if (event.screen is GuiDisconnected && (event.screen as GuiDisconnected).message.unformattedText == message) {
                var session: Session? = null
                if (type == Type.MOJANG) {
                    val authenticationService = YggdrasilAuthenticationService(mc.proxy, UUID.randomUUID().toString())
                    val userAuthentication = authenticationService.createUserAuthentication(Agent.MINECRAFT)

                    userAuthentication.setUsername(email)
                    userAuthentication.setPassword(password)

                    try {
                        userAuthentication.logIn()
                        session = Session(
                            userAuthentication.selectedProfile.name,
                            UUIDTypeAdapter.fromUUID(userAuthentication.selectedProfile.id),
                            userAuthentication.authenticatedToken,
                            userAuthentication.userType.name
                        )

                        userAuthentication.logOut()
                    } catch (e: AuthenticationException) {
                        LambdaAuth.logger.error("Failed to authenticate with mojang", e)
                    }
                } else {
                    AuthCommand.authResult?.accessToken()?.let { token ->
                        HttpClients.createDefault().use { client ->
                            xblAuth(client, token).use { response ->
                                val responseBody = gson.fromJson(EntityUtils.toString(response.entity), jsonClass)
                                val xblToken = responseBody["Token"].asString
                                val userHash = responseBody["DisplayClaims"]
                                    .asJsonObject["xui"]
                                    .asJsonArray[0]
                                    .asJsonObject["uhs"]
                                    .asString
                                xstsAuth(client, xblToken).use { xstsResponse ->
                                    val xstsResponseBody = gson.fromJson(EntityUtils.toString(xstsResponse.entity), jsonClass)
                                    if (xstsResponse.statusLine.statusCode == 401) {
                                        LambdaAuth.logger.error("XSTS auth returned 401 with XErr ${xstsResponseBody["XErr"].asLong}")
                                        return@listener
                                    }
                                    val xstsToken = xstsResponseBody["Token"].asString
                                    minecraftAuth(client, xstsToken, userHash).use { mcResponse ->
                                        val mcToken = gson.fromJson(EntityUtils.toString(mcResponse.entity), jsonClass)["access_token"]
                                            .asString
                                        if (!hasMinecraft(client, mcToken)) {
                                            LambdaAuth.logger.error("Account does not own minecraft")
                                            return@listener
                                        }
                                        getProfile(client, mcToken).use { profile ->
                                            val profileBody = gson.fromJson(EntityUtils.toString(profile.entity), jsonClass)
                                            val uuid = profileBody["id"].asString
                                            val name = profileBody["name"].asString

                                            session = Session(
                                                name,
                                                uuid,
                                                mcToken,
                                                UserType.MOJANG.name
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (session != null) {
                    ObfuscationReflectionHelper.setPrivateValue(mc.javaClass, mc, session, "field_71449_j")
                    mc.displayGuiScreen(GuiConnecting(event.screen!!, mc, mc.currentServerData!!))
                }
            }
        }
    }

    private fun xblAuth(client: CloseableHttpClient, token: String): CloseableHttpResponse {
        val innerObject = JsonObject()
        innerObject.addProperty("AuthMethod", "RPS")
        innerObject.addProperty("SiteName", "user.auth.xboxlive.com")
        innerObject.addProperty("RpsTicket", "d=$token")

        val bodyObject = JsonObject()
        bodyObject.add("Properties", innerObject)
        bodyObject.addProperty("RelyingParty", "http://auth.xboxlive.com")
        bodyObject.addProperty("TokenType", "JWT")

        val request = HttpPost("https://user.auth.xboxlive.com/user/authenticate")
        request.entity = StringEntity(bodyObject.toString())
        request.setHeader("Content-Type", "application/json")
        request.setHeader("Accept", "application/json")

        return client.execute(request)
    }

    private fun xstsAuth(client: CloseableHttpClient, token: String): CloseableHttpResponse {
        val innerObject = JsonObject()
        innerObject.addProperty("SandboxId", "RETAIL")
        val tokenArray = JsonArray()
        tokenArray.add(token)
        innerObject.add("UserTokens", tokenArray)

        val bodyObject = JsonObject()
        bodyObject.add("Properties", innerObject)
        bodyObject.addProperty("RelyingParty", "rp://api.minecraftservices.com/")
        bodyObject.addProperty("TokenType", "JWT")

        val request = HttpPost("https://xsts.auth.xboxlive.com/xsts/authorize")
        request.entity = StringEntity(bodyObject.toString())
        request.setHeader("Content-Type", "application/json")
        request.setHeader("Accept", "application/json")

        return client.execute(request)
    }

    private fun minecraftAuth(client: CloseableHttpClient, token: String, userHash: String): CloseableHttpResponse {
        val bodyObject = JsonObject()
        bodyObject.addProperty("identityToken", "XBL3.0 x=$userHash;$token")

        val request = HttpPost("https://api.minecraftservices.com/authentication/login_with_xbox")
        request.entity = StringEntity(bodyObject.toString())
        // Maybe not necessary?
        request.setHeader("Content-Type", "application/json")
        request.setHeader("Accept", "application/json")

        return client.execute(request)
    }

    private fun hasMinecraft(client: CloseableHttpClient, token: String): Boolean {
        val request = HttpGet("https://api.minecraftservices.com/entitlements/mcstore")
        request.setHeader("Authorization", "Bearer $token")

        client.execute(request).use {
            val items = gson.fromJson(EntityUtils.toString(it.entity), jsonClass)["items"].asJsonArray
            return items.size() != 0
        }
    }

    private fun getProfile(client: CloseableHttpClient, token: String): CloseableHttpResponse {
        val request = HttpGet("https://api.minecraftservices.com/minecraft/profile")
        request.setHeader("Authorization", "Bearer $token")

        return client.execute(request)
    }
}