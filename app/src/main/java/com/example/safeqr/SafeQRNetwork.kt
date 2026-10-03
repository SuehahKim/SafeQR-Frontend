package com.example.safeqr

import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

// 1. 서버로 보낼 데이터 상자 (Request)
data class AnalyzeRequest(
    val url: String
)

// 2. 서버에서 받을 데이터 상자 (Response)
data class AnalyzeResponse(
    val url: String,
    val domain: String,
    val score: Int,
    val level: String, // "safe", "warning", "danger"
    val reasons: List<String>
)

// 3. 택배 기사(Retrofit)가 사용할 주소록
interface SafeQRApi {
    @POST("/analyze")
    fun analyzeUrl(@Body request: AnalyzeRequest): Call<AnalyzeResponse>
}

// 4. 택배 기사(Retrofit) 회사 설립
object NetworkManager {
    private const val BASE_URL = "https://safeqr.onrender.com" // 영채님이 만든 서버 주소

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val api: SafeQRApi = retrofit.create(SafeQRApi::class.java)
}