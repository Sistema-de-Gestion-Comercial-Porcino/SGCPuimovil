package bo.edu.univalle.sgcp

import java.math.BigDecimal

/*
 * Patrón Command (RNF-01).
 *
 * Una captura sin conexión no se puede enviar en el momento, así que se convierte
 * en una orden: un objeto con todo lo necesario para ejecutarse después
 * (clave, versión base y datos). La orden se guarda en Room, se encola y se
 * reintenta con la misma clave y el mismo contenido hasta que el servidor responda.
 *
 *   ColaRecepciones (invocador) --> ComandoEnvio (orden) --> ClienteRecepcionAPI (receptor)
 */

/** Evidencia tal como viaja a la API: tipo y contenido en base64. */
data class EvidenciaDto(val tipo: String, val contenidoBase64: String)

/** Un elemento de `POST recepciones/sincronizar`, con los nombres del contrato de SGCPapi. */
data class RecepcionDto(
    val claveUnica: String,
    val entregaId: String,
    val versionBase: Int,
    val autorId: String,
    val fechaReal: String,
    val pesoRecibido: BigDecimal,
    val unidad: String,
    val conforme: Boolean,
    val receptor: String,
    val observacion: String,
    val evidencias: List<EvidenciaDto>
)

/** Lo que puede pasar al ejecutar una orden. */
sealed class Resultado {
    /** El servidor guardó la recepción, o ya la tenía (reintento): las dos son éxito. */
    data class Confirmado(val recepcionId: String, val fechaSincronizacion: String?, val duplicada: Boolean) : Resultado()

    /** El servidor la guardó en CONFLICTO: espera revisión humana. */
    data class EnConflicto(val recepcionId: String, val motivo: String) : Resultado()

    /** El servidor la rechazó por una regla (foto inválida, clave reutilizada...). No se reintenta sola. */
    data class Rechazado(val codigo: String, val mensaje: String) : Resultado()

    /** Sin red, tiempo agotado, error 5xx o 409 de concurrencia: se reintenta igual más tarde. */
    data class SinConexion(val mensaje: String) : Resultado()

    /** El token venció: hay que volver a iniciar sesión antes de reintentar. */
    data object SesionVencida : Resultado()
}

/** El receptor del patrón: quien sabe hablar con la API. */
interface ClienteRecepcionAPI {
    suspend fun registrar(datos: RecepcionDto): Resultado

    /** Estado central de una recepción ya enviada (CONFIRMADA, CONFLICTO o DESCARTADA); null si no se pudo consultar. */
    suspend fun consultarEstado(recepcionId: String): String?
}

/** La orden abstracta. */
interface ComandoEnvio {
    val clave: String
    suspend fun ejecutar(): Resultado
}

/** La orden concreta: enviar una recepción capturada. */
class ComandoRecepcion(
    private val datos: RecepcionDto,
    private val receptor: ClienteRecepcionAPI
) : ComandoEnvio {
    override val clave: String get() = datos.claveUnica
    val versionBase: Int get() = datos.versionBase

    override suspend fun ejecutar(): Resultado = receptor.registrar(datos)
}
