package me.nepnep.lambdaauth

import com.lambda.client.event.events.GuiEvent
import com.lambda.client.mixin.extension.message
import com.lambda.client.module.Category
import com.lambda.client.plugin.api.PluginModule
import com.lambda.event.listener.listener
import com.mojang.authlib.Agent
import com.mojang.authlib.exceptions.AuthenticationException
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService
import com.mojang.util.UUIDTypeAdapter
import net.minecraft.client.gui.GuiDisconnected
import net.minecraft.client.multiplayer.GuiConnecting
import net.minecraft.client.resources.I18n
import net.minecraft.util.Session
import net.minecraftforge.fml.common.ObfuscationReflectionHelper
import org.apache.logging.log4j.LogManager
import java.util.*

internal object AuthModule: PluginModule(
    name = "LambdaAuth",
    category = Category.MISC,
    description = "Automatically fixes the invalid session error",
    pluginMain = LambdaAuth
) {
    private val email by setting("Email", "")
    private val password by setting("Password", "")

    init {
        listener<GuiEvent.Displayed> {
            val message = I18n.format("disconnect.loginFailed") + ": " + I18n.format("disconnect.loginFailedInfo.invalidSession")

            if (it.screen is GuiDisconnected && (it.screen as GuiDisconnected).message.unformattedText == message) {
                val authenticationService = YggdrasilAuthenticationService(mc.proxy, UUID.randomUUID().toString())
                val userAuthentication = authenticationService.createUserAuthentication(Agent.MINECRAFT)

                userAuthentication.setUsername(email)
                userAuthentication.setPassword(password)

                try {
                    userAuthentication.logIn()
                    ObfuscationReflectionHelper.setPrivateValue(mc.javaClass, mc, Session(
                        userAuthentication.selectedProfile.name,
                        UUIDTypeAdapter.fromUUID(userAuthentication.selectedProfile.id),
                        userAuthentication.authenticatedToken,
                        userAuthentication.userType.name
                    ), "field_71449_j")
                    
                    mc.displayGuiScreen(GuiConnecting(it.screen!!, mc, mc.currentServerData!!))
                    userAuthentication.logOut()
                } catch (e: AuthenticationException) {
                    LogManager.getLogger("lambda-auth").error("Failed to authenticate", e)
                }
            }
        }
    }
}