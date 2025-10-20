package com.pira.ccloud.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

open class BaseRepository {
    protected val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    protected val API_KEY = "4F5A9C3D9A86FA54EACEDDD635185"
    
    // Helper servers array
    protected val helperServers = arrayOf(
        "https://hostinnegar.com",
        "https://windowsdiba.info"
    )
    
    protected suspend fun executeRequest(
        primaryUrl: String,
        requestBuilder: (String) -> Request
    ): String {
        return withContext(Dispatchers.IO) {
            // First, try the primary server
            try {
                val primaryRequest = requestBuilder(primaryUrl)
                client.newCall(primaryRequest).execute().use { response ->
                    if (response.isSuccessful) {
                        return@withContext response.body?.string()
                            ?: throw Exception("Empty response body from primary server")
                    } else {
                        throw Exception("Primary server returned error: ${response.code}")
                    }
                }
            } catch (primaryException: Exception) {
                // If primary server fails, try helper servers
                for (helperServer in helperServers) {
                    try {
                        // Replace the host in the URL with the helper server
                        val helperUrl = primaryUrl.replace(Regex("^https?://[^/]+"), helperServer)
                        val helperRequest = requestBuilder(helperUrl)
                        client.newCall(helperRequest).execute().use { response ->
                            if (response.isSuccessful) {
                                return@withContext response.body?.string()
                                    ?: throw Exception("Empty response body from helper server")
                            }
                        }
                    } catch (helperException: Exception) {
                        // Continue to next helper server
                        continue
                    }
                }
                
                // If all servers fail, throw the original exception
                throw primaryException
            }
        }
    }
}
