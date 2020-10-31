package com.kravz.delimap

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.provider.Settings
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException


class Mailer(private val context: Context) {
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient()
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun sendNotify(attrs: Array<String>, onSend: () -> Unit, onError: (e: Exception) -> Unit) = try {
        val mailMessage = StringBuilder().apply {
            append("<html><body>")
            append(attrs.joinToString("\n"))
            append("</html></body>") }.toString()

        val mailSender = try {
            getEmail(context) ?: ""
        } catch (e: Exception) {
            "" }

        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        } catch (e: Exception) {
            "" }

        val jsonObj = JSONObject().apply {
            put("message", mailMessage)
            if (androidId.isEmpty())
                put("android_id", androidId)
            if (mailSender.isEmpty())
                put("sender", mailSender) }

        val body = jsonObj.toString().toRequestBody(JSON)
        val request = Request.Builder()
            .url("http://notifier.mt03.ru/index.php")
            .post(body)
            .build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(e) }

            override fun onResponse(call: Call, response: Response) {
                onSend() }})
    } catch (e: Exception) {
        onError(e) }

    private fun getEmail(context: Context?): String? {
        val accountManager = AccountManager.get(context)
        return getAccount(accountManager)?.name }

    private fun getAccount(accountManager: AccountManager): Account? {
        val accounts = accountManager.getAccountsByType("com.google")
        return accounts.getOrNull(0) }
}