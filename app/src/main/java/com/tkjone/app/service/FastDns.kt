package com.tkjone.app.service

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DNS over HTTPS (DoH) using Cloudflare for faster DNS.
 * Uses a SEPARATE OkHttpClient with system DNS to avoid infinite recursion.
 */
object FastDns : Dns {

    private val cache = ConcurrentHashMap<String, List<InetAddress>>()
    private val cacheTime = ConcurrentHashMap<String, Long>()
    private const val CACHE_TTL = 5 * 60 * 1000L

    // CRITICAL: This client must NOT use FastDns — use system DNS to avoid recursion
    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val DOH_URL get() = com.tkjone.app.config.AppConfig.DOH_URL

    override fun lookup(hostname: String): List<InetAddress> {
        // Skip for local addresses
        if (hostname == "localhost" || hostname.startsWith("192.168.") ||
            hostname.startsWith("10.") || hostname.startsWith("172.")) {
            return InetAddress.getAllByName(hostname).toList()
        }

        // Check cache
        val cached = cache[hostname]
        val cachedAt = cacheTime[hostname] ?: 0
        if (cached != null && System.currentTimeMillis() - cachedAt < CACHE_TTL) {
            return cached
        }

        // Try DoH via separate client
        return try {
            val result = resolveViaDoH(hostname)
            if (result.isNotEmpty()) {
                cache[hostname] = result
                cacheTime[hostname] = System.currentTimeMillis()
                result
            } else {
                fallbackSystemDns(hostname)
            }
        } catch (_: Exception) {
            fallbackSystemDns(hostname)
        }
    }

    private fun resolveViaDoH(hostname: String): List<InetAddress> {
        val url = "$DOH_URL?name=$hostname&type=A"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/dns-json")
            .build()

        val response = dohClient.newCall(request).execute()
        if (!response.isSuccessful) return emptyList()

        val body = response.body?.string() ?: return emptyList()
        val json = JSONObject(body)
        val answers = json.optJSONArray("Answer") ?: return emptyList()

        val results = mutableListOf<InetAddress>()
        for (i in 0 until answers.length()) {
            val answer = answers.getJSONObject(i)
            val type = answer.optInt("type", 0)
            val data = answer.optString("data", "")
            if (type == 1 && data.isNotEmpty()) {
                try { results.add(InetAddress.getByName(data)) } catch (_: Exception) {}
            }
        }
        return results
    }

    private fun fallbackSystemDns(hostname: String): List<InetAddress> {
        return try {
            val result = InetAddress.getAllByName(hostname).toList()
            if (result.isNotEmpty()) {
                cache[hostname] = result
                cacheTime[hostname] = System.currentTimeMillis()
            }
            result
        } catch (e: Exception) {
            throw e
        }
    }

    fun clearCache() {
        cache.clear()
        cacheTime.clear()
    }
}
