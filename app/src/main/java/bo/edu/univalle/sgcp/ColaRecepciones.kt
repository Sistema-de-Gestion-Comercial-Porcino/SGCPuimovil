package bo.edu.univalle.sgcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.util.Base64

/**
 * Una captura guardada en el teléfono (RNF-01): clave única, versión base, autor,
 * fecha real, datos y la ruta privada de cada evidencia.
 */
data class RegistroEnvio(
    val clave: String,
    val entregaId: String,
    val versionBase: Int,
    val autorId: String,
    val fechaReal: String,
    val pesoRecibido: String,
    val unidad: String,
    val conforme: Boolean,
    val receptor: String,
    val observacion: String,
    val evidencias: List<String>,
    val estado: String = Pendiente.nombre,
    val recepcionId: String? = null,
    val fechaSincronizacion: String? = null,
    val motivo: String? = null,
    val creadaEn: Long = System.currentTimeMillis()
)

/** Donde se persisten las órdenes. La implementación real es Room (AlmacenPendientesRoom). */
interface AlmacenPendientes {
    suspend fun guardar(registro: RegistroEnvio)
    /** Las capturas en PENDIENTE, en el orden en que se capturaron. */
    suspend fun listar(): List<RegistroEnvio>
    suspend fun enConflicto(): List<RegistroEnvio>
    suspend fun actualizar(sincronizacion: SincronizacionRecepcion)
    /** Capturas que quedaron en ENVIANDO porque la app se cerró a mitad del envío. */
    suspend fun enviando(): List<RegistroEnvio>
}

data class ResumenSincronizacion(
    val confirmadas: Int,
    val conflictos: Int,
    val pendientes: Int,
    val mensaje: String?,
    val sesionVencida: Boolean = false
)

/**
 * El invocador del patrón Command. Toma las órdenes guardadas, las ejecuta una por
 * una en el orden de captura y aplica a cada una el evento que corresponde del
 * patrón State. Se envía una por solicitud: si una captura es inválida, el servidor
 * rechaza el lote entero, y así una mala no bloquea a las demás.
 */
class ColaRecepciones(
    private val almacen: AlmacenPendientes,
    private val cliente: ClienteRecepcionAPI,
    private val leerEvidencia: (String) -> ByteArray
) {
    private val candado = Mutex()

    fun comandoPara(registro: RegistroEnvio): ComandoRecepcion = ComandoRecepcion(armar(registro), cliente)

    /** Arma el cuerpo siempre igual a partir de lo guardado: el reintento manda el mismo contenido. */
    fun armar(r: RegistroEnvio) = RecepcionDto(
        claveUnica = r.clave,
        entregaId = r.entregaId,
        versionBase = r.versionBase,
        autorId = r.autorId,
        fechaReal = r.fechaReal,
        pesoRecibido = BigDecimal(r.pesoRecibido),
        unidad = r.unidad,
        conforme = r.conforme,
        receptor = r.receptor,
        observacion = r.observacion,
        evidencias = r.evidencias.map { EvidenciaDto("image/jpeg", Base64.getEncoder().encodeToString(leerEvidencia(it))) }
    )

    suspend fun sincronizar(): ResumenSincronizacion = candado.withLock {
        // Si la app se cerró a mitad de un envío, esas capturas vuelven a PENDIENTE.
        for (r in almacen.enviando()) {
            val s = contexto(r)
            s.manejar(Evento.PerderConexion("El envío anterior no terminó."))
            almacen.actualizar(s)
        }
        var confirmadas = 0
        var conflictos = 0
        var mensaje: String? = null
        var sesionVencida = false
        for (registro in almacen.listar()) {
            val s = contexto(registro)
            s.manejar(Evento.Sincronizar)
            almacen.actualizar(s)
            val resultado = runCatching { comandoPara(registro).ejecutar() }
                .getOrElse { Resultado.SinConexion(it.message ?: "No se pudo leer la captura.") }
            when (resultado) {
                is Resultado.Confirmado -> {
                    s.manejar(Evento.RecibirConfirmacion(resultado.recepcionId, resultado.fechaSincronizacion))
                    confirmadas++
                }
                is Resultado.EnConflicto -> {
                    s.manejar(Evento.RecibirConflicto(resultado.motivo, resultado.recepcionId))
                    conflictos++
                }
                is Resultado.Rechazado -> {
                    s.manejar(Evento.RecibirConflicto("Rechazada por el servidor (${resultado.codigo}): ${resultado.mensaje}", null))
                    conflictos++
                }
                is Resultado.SinConexion -> {
                    s.manejar(Evento.PerderConexion(resultado.mensaje))
                    mensaje = resultado.mensaje
                }
                is Resultado.SesionVencida -> {
                    s.manejar(Evento.PerderConexion("La sesión venció."))
                    sesionVencida = true
                    mensaje = "Tu sesión venció. Vuelve a iniciar sesión para enviar."
                }
            }
            almacen.actualizar(s)
            // Sin red o sin sesión no tiene sentido intentar las siguientes: se conserva el orden.
            if (resultado is Resultado.SinConexion || resultado is Resultado.SesionVencida) break
        }
        ResumenSincronizacion(confirmadas, conflictos, almacen.listar().size, mensaje, sesionVencida)
    }

    /**
     * Pregunta al servidor por los conflictos ya enviados. Solo una persona autorizada
     * los resuelve en la web: aquí únicamente se refleja lo que decidió.
     */
    suspend fun revisarConflictos(): Int = candado.withLock {
        var cambios = 0
        for (registro in almacen.enConflicto()) {
            val id = registro.recepcionId ?: continue
            val evento = when (cliente.consultarEstado(id)) {
                Confirmada.nombre -> Evento.RevisionAplicada
                Descartada.nombre -> Evento.DescarteAutorizado
                else -> null
            } ?: continue
            val s = contexto(registro)
            s.manejar(evento)
            almacen.actualizar(s)
            cambios++
        }
        cambios
    }

    private fun contexto(r: RegistroEnvio) = SincronizacionRecepcion(
        r.clave,
        SincronizacionRecepcion.estadoPorNombre(r.estado),
        r.recepcionId,
        r.fechaSincronizacion,
        r.motivo
    )
}
