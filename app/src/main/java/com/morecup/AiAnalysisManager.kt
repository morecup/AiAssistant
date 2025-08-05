package com.morecup

import android.content.Context
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONException
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
                .header("Cookie","sensorsdata2015jssdkcross=%7B%22distinct_id%22%3A%22100023302585%22%2C%22first_id%22%3A%22194efdd090b1905-0ad2353c9c7ac48-f505725-2359296-194efdd090c1a31%22%2C%22props%22%3A%7B%22%24latest_traffic_source_type%22%3A%22%E8%87%AA%E7%84%B6%E6%90%9C%E7%B4%A2%E6%B5%81%E9%87%8F%22%7D%2C%22identities%22%3A%22eyIkaWRlbnRpdHlfY29va2llX2lkIjoiMTk0ZWZkZDA5MGIxOTA1LTBhZDIzNTNjOWM3YWM0OC1mNTA1NzI1LTIzNTkyOTYtMTk0ZWZkZDA5MGMxYTMxIiwiJGlkZW50aXR5X2xvZ2luX2lkIjoiMTAwMDIzMzAyNTg1In0%3D%22%2C%22history_login_id%22%3A%7B%22name%22%3A%22%24identity_login_id%22%2C%22value%22%3A%22100023302585%22%7D%2C%22%24device_id%22%3A%22194efdd090b1905-0ad2353c9c7ac48-f505725-2359296-194efdd090c1a31%22%7D; _qimei_uuid42=192160c3906100b200b14856e53bce813b63b0dc3e; _qimei_fingerprint=eb3d7de27e9db3690cafe60342b1ef3f; _qimei_i_3=60c86f85940e59dec7c5fc61098670e9a2bfa7f2140d0383e5892b0e279b7669646332943b89e29eacb1; _qimei_h38=527f6a4000b14856e53bce8102000005719216; _qimei_i_1=58dc4587975d06dd9093fe305bd672e4ffeba2a4175851d1e18729582493206c616332923980e0ddd088aee5; hy_user=197a63bc879048ab823f40ceb3c20131; hy_token=8tE8bq6InCxff5mUqQZfc9aGHP6NPD80Cr/k258SiLJ0SRKVmpnUylkLLyDfCVTFSxwtt32qb1K21rQM8ixQFvSmHCGxCBY9izs2HVhSGn+azXhA0kELiTsS8pkJcBmfLor0dAyRTdaL1BGz1FDsQBlGYZqtDRangLMISpWZxFA2i2VcMzr6JFbgJxSh0Lut8rAgiXYn9yPCuPGgU6ZQ/ULIF3dzylxKQX6EsqChwHtk9J/VuC/0w1z9HCLRWz58J4ItVYtAIca1JjomavzGqEL3SqIrsPCNx1Rn+w2Enf2Yliv9xg5azGOMDfzPVr2RQ19huiNuLCBcS+v5vpug/9+SqSTKCEKns1JW/b8zQGBvG3UZvNNa3F143amZv+NkNzbK9QO3+Mpbxvwf6kVhbMF1NH3UVL9A6vBpYlIsUJmiNYSaETRmfZ5zcpPIfINzR6RqMj6H93gbgKivuKRuvxr/olGVRdclFRFYbC637/irZRf9kg0/V2kwqYtDbwrmuxUjOU/nc8zh9irp2/jvKv2XSq5jVbd//LYYxKMmD0dXR9sdhsUreN8GAGz0S6DglOT78WzZ12i8BeP8ssBNDLxlPAPWU5uaOQwyn0zEV7I=; hy_source=web")
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
        val requestBody = JSONObject()
        try {
            // 基础参数
            requestBody.put("model", "gpt_175B_0404")
            requestBody.put("prompt", query) // 用户输入的查询内容
            requestBody.put("plugin", "Adaptive")
            requestBody.put("displayPrompt", query)
            requestBody.put("displayPromptType", 1)

            // 嵌套 options 对象
            val imageIntention = JSONObject()
            imageIntention.put("needIntentionModel", true)
            imageIntention.put("backendUpdateFlag", 2)
            imageIntention.put("intentionStatus", true)

            val options = JSONObject()
            options.put("imageIntention", imageIntention)
            requestBody.put("options", options)

            // 多媒体数组（空数组）
            requestBody.put("multimedia", JSONArray())

            // 固定参数
            requestBody.put("agentId", "naQivTmsDa")
            requestBody.put("supportHint", 1)
            requestBody.put("extReportParams", JSONObject.NULL) // 显式声明 null
            requestBody.put("isAtomInput", false)
            requestBody.put("version", "v2")
            requestBody.put("chatModelId", "deep_seek_v3")

            // 需要转义的 JSON 字符串参数
            val extInfo =
                "{\"modelId\":\"deep_seek_v3\",\"subModelId\":\"\",\"supportFunctions\":{\"internetSearch\":\"closeInternetSearch\"}}"
            requestBody.put("chatModelExtInfo", extInfo)

            // 空数组参数
            requestBody.put("applicationIdList", JSONArray())

            // 功能支持数组
            val supportFunctions = JSONArray()
            supportFunctions.put("closeInternetSearch")
            requestBody.put("supportFunctions", supportFunctions)
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        
        return RequestBody.create(
            "application/json".toMediaTypeOrNull(),
            requestBody.toString(),
        )
    }
    
    fun cancelAllRequests() {
        client.dispatcher.cancelAll()
    }

    fun stop(){
        client.dispatcher.executorService.shutdown()
    }
}