package bo.edu.univalle.sgcp

import com.google.gson.Gson
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.io.IOException
import java.util.concurrent.TimeUnit

/* Contrato de SGCPapi para Android (README de SGCPapi, «Sincronización de recepción para Android»). */

data class EntregaAsignada(
    val id: String,
    val version: Int,
    val pedidoId: String,
    val pedido: String,
    val rutaId: String?,
    val fechaPrevista: String,
    val estado: String,
    val cliente: String,
    val direccion: String?,
    val telefono: String?
)

data class DetalleAsignado(val pedidoId: String, val producto: String, val cantidadConfirmada: java.math.BigDecimal, val unidad: String)
data class MisEntregasRespuesta(val entregas: List<EntregaAsignada>, val detalles: List<DetalleAsignado>)

data class SincronizarPeticion(val recepciones: List<RecepcionDto>)
data class ResultadoSincronizacion(
    val claveUnica: String,
    val recepcionId: String,
    val estado: String,
    val duplicada: Boolean,
    val fechaSincronizacion: String?
)
data class SincronizarRespuesta(val resultados: List<ResultadoSincronizacion>)

data class RecepcionCentral(val id: String, val estado: String, val motivoConflicto: String?)
data class VerRecepcionRespuesta(val recepcion: RecepcionCentral)

interface ServicioReparto {
    @GET("mis-entregas")
    suspend fun misEntregas(@Header("Authorization") token: String): Response<MisEntregasRespuesta>

    @POST("recepciones/sincronizar")
    suspend fun sincronizar(@Header("Authorization") token: String, @Body peticion: SincronizarPeticion): Response<SincronizarRespuesta>

    @GET("recepciones/{id}")
    suspend fun recepcion(@Header("Authorization") token: String, @Path("id") id: String): Response<VerRecepcionRespuesta>

    @POST("rutas/{id}/finalizar")
    suspend fun finalizarRuta(@Header("Authorization") token: String, @Path("id") id: String): Response<Unit>
}

object ConexionReparto {
    /* El envío lleva fotos: más tiempo de escritura y lectura que el acceso. */
    fun crear(url: String): ServicioReparto = Retrofit.Builder().baseUrl(Conexion.validar(url, BuildConfig.DEBUG))
        .client(
            OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(ServicioReparto::class.java)

    fun error(respuesta: Response<*>): ErrorApi? =
        runCatching { Gson().fromJson(respuesta.errorBody()?.string(), ErrorApi::class.java) }.getOrNull()
}

/** El receptor real del patrón Command: traduce cada respuesta HTTP a un Resultado. */
class ClienteRecepcionRetrofit(
    private val servicio: ServicioReparto,
    private val token: String
) : ClienteRecepcionAPI {
    override suspend fun registrar(datos: RecepcionDto): Resultado {
        val respuesta = try {
            servicio.sincronizar("Bearer $token", SincronizarPeticion(listOf(datos)))
        } catch (e: IOException) {
            return Resultado.SinConexion("Sin conexión con el servidor.")
        }
        return interpretar(respuesta, datos.claveUnica)
    }

    override suspend fun consultarEstado(recepcionId: String): String? = try {
        servicio.recepcion("Bearer $token", recepcionId).body()?.recepcion?.estado
    } catch (e: IOException) {
        null
    }

    companion object {
        fun interpretar(respuesta: Response<SincronizarRespuesta>, clave: String): Resultado {
            if (respuesta.isSuccessful) {
                val r = respuesta.body()?.resultados?.firstOrNull { it.claveUnica.equals(clave, ignoreCase = true) }
                    ?: return Resultado.SinConexion("El servidor respondió sin el resultado de la captura.")
                return when (r.estado) {
                    "CONFLICTO" -> Resultado.EnConflicto(r.recepcionId, "La entrega cambió en el servidor. Requiere revisión de administración.")
                    // Reintento de una captura que ya se revisó y descartó: queda en conflicto y la revisión la marca descartada.
                    "DESCARTADA" -> Resultado.EnConflicto(r.recepcionId, "La captura ya fue revisada en el servidor.")
                    // CONFIRMADA, nueva o ya existente: el dato llegó. «Ya existe» es éxito, no error.
                    else -> Resultado.Confirmado(r.recepcionId, r.fechaSincronizacion, r.duplicada)
                }
            }
            val codigo = respuesta.code()
            if (codigo == 401) return Resultado.SesionVencida
            // 409 de concurrencia y errores del servidor: se reintenta el mismo envío.
            if (codigo == 409 || codigo >= 500) return Resultado.SinConexion("El servidor no pudo atender el envío ($codigo).")
            val error = ConexionReparto.error(respuesta)
            return Resultado.Rechazado(error?.codigo ?: "HTTP_$codigo", error?.mensaje ?: "La captura no es válida.")
        }
    }
}
