package com.codex.astockselector.data

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

data class TushareRequest(
    val api_name: String,
    val token: String,
    val params: Map<String, Any?> = emptyMap(),
    val fields: String = "",
)

data class TushareResponse(
    val code: Int,
    val msg: String?,
    val data: TushareTable?,
)

data class TushareTable(
    val fields: List<String>,
    val items: List<List<Any?>>,
)

interface TushareApi {
    @POST("/")
    suspend fun query(@Body request: TushareRequest): TushareResponse
}

object TushareClient {
    fun create(): TushareApi =
        Retrofit.Builder()
            .baseUrl("https://api.tushare.pro/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TushareApi::class.java)
}
