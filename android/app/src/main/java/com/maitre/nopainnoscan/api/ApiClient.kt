package com.maitre.nopainnoscan.api

import android.content.Context
import com.maitre.nopainnoscan.AppPrefs
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// Miroir des schémas Pydantic du backend : noms snake_case conservés pour Gson.
// Gson ignore les valeurs par défaut Kotlin : tout champ absent de la réponse arrive null.

data class UserDto(val id: Int, val name: String)

data class ProfileDto(
    val sex: String, // male | female
    val age: Int,
    val height_cm: Double,
    val weight_kg: Double,
    val neck_cm: Double?,
    val waist_cm: Double?,
    val hips_cm: Double?,
    val activity: String, // sedentary | light | moderate | active | athlete
    val goal: String, // cut | maintenance | bulk
    val target_body_fat_pct: Double?,
    val daily_kcal_target: Double?, // null = cible calculée
    val daily_protein_target_g: Double?,
)

data class MessageDto(val level: String, val field: String, val text: String)

data class EstimateDto(
    val body_fat_pct: Double?,
    val lean_mass_kg: Double?,
    val bmr_kcal: Int,
    val tdee_kcal: Int,
    val kcal_target_auto: Int,
    val protein_target_auto: Int,
    val kcal_target: Int,
    val protein_target_g: Int,
    val messages: List<MessageDto>,
)

data class ProfileOutDto(
    val sex: String,
    val age: Int,
    val height_cm: Double,
    val weight_kg: Double,
    val neck_cm: Double?,
    val waist_cm: Double?,
    val hips_cm: Double?,
    val activity: String,
    val goal: String,
    val target_body_fat_pct: Double?,
    val daily_kcal_target: Double?,
    val daily_protein_target_g: Double?,
    val estimate: EstimateDto,
)

data class NutrientsDto(
    val name: String,
    val category: String? = null,
    val kcal_100g: Double,
    val protein_100g: Double = 0.0,
    val carbs_100g: Double = 0.0,
    val sugars_100g: Double = 0.0,
    val fat_100g: Double = 0.0,
    val saturated_fat_100g: Double = 0.0,
    val fiber_100g: Double = 0.0,
    val salt_100g: Double = 0.0,
)

data class AlternativeDto(val product_id: Int, val name: String, val score: Double, val category: String)

data class ScoreDto(
    val product_id: Int,
    val product_name: String,
    val category: String, // parfait | pas_mal | a_eviter | a_ne_pas_manger
    val score: Double,
    val breakdown: Map<String, Double>,
    val source: String,
    val store: String?,
    val alternatives: List<AlternativeDto>,
    val alternatives_scope: String?, // store | any
)

data class ScanDto(
    val id: Int,
    val product_id: Int,
    val product_name: String,
    val store: String?,
    val score: Double,
    val category: String,
    val created_at: String,
)

data class CategoryDto(val slug: String, val label: String)

data class RecommendationDto(
    val product_id: Int,
    val name: String,
    val score: Double,
    val category: String,
    val kcal_100g: Double,
    val protein_100g: Double,
)

data class RecommendationsDto(
    val category: String,
    val store: String?,
    val scope: String, // store | any
    val items: List<RecommendationDto>,
)

interface NoPainNoScanApi {
    @GET("categories")
    suspend fun categories(): List<CategoryDto>

    @GET("recommendations")
    suspend fun recommendations(
        @Query("category") category: String,
        @Query("store") store: String?,
        @Query("limit") limit: Int = 20,
    ): RecommendationsDto

    @GET("me")
    suspend fun me(): UserDto

    @GET("profile")
    suspend fun getProfile(): ProfileOutDto

    @PUT("profile")
    suspend fun setProfile(@Body profile: ProfileDto): ProfileOutDto

    @POST("profile/estimate")
    suspend fun estimate(@Body profile: ProfileDto): EstimateDto

    @GET("scan/barcode/{barcode}")
    suspend fun scanBarcode(@Path("barcode") barcode: String, @Query("store") store: String?): ScoreDto

    @POST("scan/manual")
    suspend fun scanManual(@Body nutrients: NutrientsDto, @Query("store") store: String?): ScoreDto

    @GET("scans")
    suspend fun history(@Query("limit") limit: Int = 50): List<ScanDto>

    @GET("products/{id}")
    suspend fun product(@Path("id") id: Int, @Query("store") store: String?): ScoreDto
}

/**
 * Un seul Retrofit/OkHttp partagé (pool de connexions), reconstruit uniquement
 * quand l'URL ou la clé changent dans les réglages.
 */
object ApiClient {
    @Volatile private var cached: Pair<String, NoPainNoScanApi>? = null

    fun get(context: Context): NoPainNoScanApi {
        val prefs = AppPrefs(context)
        val signature = prefs.apiBaseUrl + " " + prefs.apiKey
        cached?.takeIf { it.first == signature }?.let { return it.second }
        return synchronized(this) {
            cached?.takeIf { it.first == signature }?.second
                ?: build(prefs.apiBaseUrl, prefs.apiKey).also { cached = signature to it }
        }
    }

    private fun build(baseUrl: String, apiKey: String): NoPainNoScanApi {
        val http = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS) // premier scan = aller-retour Open Food Facts côté serveur
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Api-Key", apiKey).build())
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NoPainNoScanApi::class.java)
    }
}
