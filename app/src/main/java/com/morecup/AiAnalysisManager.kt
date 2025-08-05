package com.morecup

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class AiAnalysisManager(
    private val context: Context,
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val BASE_URL = "https://yuanbao.tencent.com/api/chat/1859758a-1c39-4d37-8ce5-92a38edf70a0"
    
    interface AiAnalysisCallback {
        fun onSuccess(response: String) {}
        fun onError(error: String)
        fun onStreamText(text: String) {} // 流式文本回调
        fun onStreamComplete() {} // 流式响应完成回调
    }
    
    fun analyzeText(query: String, callback: AiAnalysisCallback) {
        try {
            val requestBody = createRequestBody(query)
            val request = Request.Builder()
                .url(BASE_URL)
                .post(requestBody)
                .header("Content-Type", "application/json")
                .build()
            
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    callback.onError("Network error: ${e.message}")
                }
                
                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (response.isSuccessful) {
                            val responseBody = response.body
                            if (responseBody != null) {
                                processStreamResponse(responseBody, callback)
                            } else {
                                callback.onError("Empty response body")
                            }
                        } else {
                            callback.onError("HTTP error: ${response.code}")
                        }
                    } catch (e: Exception) {
                        callback.onError("Error processing response: ${e.message}")
                    } finally {
                        response.close()
                    }
                }
            })
        } catch (e: Exception) {
            callback.onError("Error building request: ${e.message}")
        }
    }
    
    private fun processStreamResponse(body: ResponseBody, callback: AiAnalysisCallback) {
        val inputStream = body.byteStream()
        val reader = BufferedReader(InputStreamReader(inputStream))
        val responseBuilder = StringBuilder()
        var line: String?

        try {
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) continue

                try {
                    if (!line!!.contains("data:")) continue
                    val json = JSONObject(line!!.replace("data:", ""))
                    val type = json.optString("type", "")
                    val msg = json.optString("msg", "")

                    if (type == "text" && msg.isNotEmpty()) {
                        responseBuilder.append(msg)
                        // 调用流式文本回调
                        callback.onStreamText(msg)
                    }
                } catch (e: Exception) {
                    Log.e("AI", "解析AI响应失败: $line", e)
                }
            }
            
            // 完整响应完成后调用onSuccess
            callback.onSuccess(responseBuilder.toString())
            callback.onStreamComplete()
        } catch (e: Exception) {
            callback.onError("Error reading stream: ${e.message}")
        } finally {
            try {
                reader.close()
                inputStream.close()
            } catch (e: IOException) {
                Log.e("AI", "关闭流时出错", e)
            }
        }
    }
    
    private fun createRequestBody(query: String): RequestBody {
        val jsonObject = JSONObject().apply {
            put("model", "gpt_175B_0404")
            put("prompt", query)
            put("plugin", "Adaptive")
            put("displayPrompt", query)
            put("displayPromptType", 1)
            
            put("options", JSONObject().apply {
                put("imageIntention", JSONObject().apply {
                    put("needIntentionModel", true)
                    put("backendUpdateFlag", 2)
                    put("intentionStatus", true)
                })
            })
            
            put("multimedia", emptyList<String>())
            put("agentId", "naQivTmsDa")
            put("supportHint", 1)
            put("extReportParams", JSONObject.NULL)
            put("isAtomInput", false)
            put("version", "v2")
            put("chatModelId", "deep_seek_v3")
            
            put("chatModelExtInfo",
                "{\"modelId\":\"deep_seek_v3\",\"subModelId\":\"\",\"supportFunctions\":{\"internetSearch\":\"closeInternetSearch\"}}"
            )
            
            put("applicationIdList", emptyList<String>())
            put("supportFunctions", listOf("closeInternetSearch"))
        }
        
        return RequestBody.create(
            "application/json".toMediaTypeOrNull(),
            jsonObject.toString()
        )
    }
    
    fun cancelAllRequests() {
        client.dispatcher.cancelAll()
    }

    fun stop(){
        client.dispatcher.executorService.shutdown()
    }
}