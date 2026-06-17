package com.codex.astockselector.data

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

data class EastmoneyStockListResponse(
    val rc: Int,
    val data: EastmoneyStockListData?,
)

data class EastmoneyStockListData(
    val total: Int,
    val diff: List<EastmoneyStockItem>?,
)

data class EastmoneyStockItem(
    @SerializedName("f12") val code: String?,
    @SerializedName("f13") val marketId: Int?,
    @SerializedName("f14") val name: String?,
    @SerializedName("f2") val price: Double?,
    @SerializedName("f3") val pctChg: Double?,
    @SerializedName("f6") val amount: Double?,
)

data class EastmoneyKLineResponse(
    val rc: Int,
    val data: EastmoneyKLineData?,
)

data class EastmoneyKLineData(
    val code: String?,
    val market: Int?,
    val name: String?,
    val klines: List<String>?,
)

interface EastmoneyQuoteApi {
    @GET("api/qt/clist/get")
    suspend fun stockList(
        @Query("pn") page: Int = 1,
        @Query("pz") pageSize: Int = 6000,
        @Query("po") order: Int = 1,
        @Query("np") np: Int = 1,
        @Query("ut") ut: String = "bd1d9ddb04089700cf9c27f6f7426281",
        @Query("fltt") fltt: Int = 2,
        @Query("invt") invt: Int = 2,
        @Query("fid") fid: String = "f3",
        @Query("fs") fs: String = "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23",
        @Query("fields") fields: String = "f12,f13,f14,f2,f3,f6",
    ): EastmoneyStockListResponse
}

interface EastmoneyHistoryApi {
    @GET("api/qt/stock/kline/get")
    suspend fun kLine(
        @Query("secid") secId: String,
        @Query("klt") klt: Int = 101,
        @Query("fqt") fqt: Int = 1,
        @Query("beg") begin: String,
        @Query("end") end: String = "20500101",
        @Query("fields1") fields1: String = "f1,f2,f3,f4,f5,f6",
        @Query("fields2") fields2: String = "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61",
    ): EastmoneyKLineResponse
}

object DirectMarketClient {
    fun quoteApi(): EastmoneyQuoteApi =
        Retrofit.Builder()
            .baseUrl("https://push2.eastmoney.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(EastmoneyQuoteApi::class.java)

    fun historyApi(): EastmoneyHistoryApi =
        Retrofit.Builder()
            .baseUrl("https://push2his.eastmoney.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(EastmoneyHistoryApi::class.java)
}
