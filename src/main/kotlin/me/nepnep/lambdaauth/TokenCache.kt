package me.nepnep.lambdaauth

import com.microsoft.aad.msal4j.ITokenCacheAccessAspect
import com.microsoft.aad.msal4j.ITokenCacheAccessContext
import java.io.FileWriter
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths

class TokenCache : ITokenCacheAccessAspect {

    private val path = "CONFIDENTIAL_TOKEN_DO_NOT_SHARE.json"
    private var data: String? = readFile()

    override fun beforeCacheAccess(iTokenCacheAccessContext: ITokenCacheAccessContext?) {
        if (data != null) {
            iTokenCacheAccessContext?.tokenCache()?.deserialize(data)
        }
    }

    override fun afterCacheAccess(iTokenCacheAccessContext: ITokenCacheAccessContext?) {
        data = iTokenCacheAccessContext?.tokenCache()?.serialize()
        data?.let { nonNullData ->
            FileWriter(path).use {
                it.write(nonNullData)
            }
        }
    }

    private fun readFile(): String? {
        return try {
            Files.readAllLines(Paths.get(path)).joinToString("")
        } catch (e: IOException) {
            if (!Files.exists(Paths.get(path))) {
                Files.createFile(Paths.get(path))
            }
            LambdaAuth.logger.error("Failed to read token cache", e)
            null
        }
    }
}