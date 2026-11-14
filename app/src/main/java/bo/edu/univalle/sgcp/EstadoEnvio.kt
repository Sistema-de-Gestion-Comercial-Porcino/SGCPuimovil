package bo.edu.univalle.sgcp

/*
 * Patrón State (RNF-01, RF-07).
 *
 * Cada captura de recepción tiene un estado de envío. Las transiciones válidas
 * viven aquí y en ningún otro lugar: las pantallas solo leen el estado.
 * Coincide con el diagrama 16_OperacionPendiente_Estados de SGCPdocumentacion:
 *
 *   PENDIENTE  --sincronizar-->            ENVIANDO
 *   ENVIANDO   --recibirConfirmacion-->    CONFIRMADA   (guardada o ya existente)
 *   ENVIANDO   --recibirConflicto-->       CONFLICTO    (versión o condición incompatible)
 *   ENVIANDO   --perderConexion-->         PENDIENTE    (se reintenta con la misma clave)
 *   CONFLICTO  --revisionAplicada-->       CONFIRMADA   (una persona la aplicó en el servidor)
 *   CONFLICTO  --descarteAutorizado-->     DESCARTADA   (una persona la descartó en el servidor)
 *
 * CONFIRMADA y DESCARTADA son terminales. Un conflicto nunca se reenvía solo.
 */

sealed class Evento {
    data object Sincronizar : Evento()
    data class RecibirConfirmacion(val recepcionId: String, val fechaSincronizacion: String?) : Evento()
    data class RecibirConflicto(val motivo: String, val recepcionId: String?) : Evento()
    data class PerderConexion(val motivo: String) : Evento()
    data object RevisionAplicada : Evento()
    data object DescarteAutorizado : Evento()
}

class TransicionInvalida(estado: String, evento: Evento) :
    IllegalStateException("La captura en estado $estado no admite ${evento::class.simpleName}.")

interface EstadoEnvio {
    val nombre: String
    fun manejar(contexto: SincronizacionRecepcion, evento: Evento)
}

object Pendiente : EstadoEnvio {
    override val nombre = "PENDIENTE"
    override fun manejar(contexto: SincronizacionRecepcion, evento: Evento) {
        when (evento) {
            is Evento.Sincronizar -> contexto.cambiar(Enviando)
            else -> throw TransicionInvalida(nombre, evento)
        }
    }
}

object Enviando : EstadoEnvio {
    override val nombre = "ENVIANDO"
    override fun manejar(contexto: SincronizacionRecepcion, evento: Evento) {
        when (evento) {
            is Evento.RecibirConfirmacion -> {
                contexto.recepcionId = evento.recepcionId
                contexto.fechaSincronizacion = evento.fechaSincronizacion
                contexto.motivo = null
                contexto.cambiar(Confirmada)
            }
            is Evento.RecibirConflicto -> {
                contexto.recepcionId = evento.recepcionId
                contexto.motivo = evento.motivo
                contexto.cambiar(Conflicto)
            }
            is Evento.PerderConexion -> {
                contexto.motivo = evento.motivo
                contexto.cambiar(Pendiente)
            }
            else -> throw TransicionInvalida(nombre, evento)
        }
    }
}

object Confirmada : EstadoEnvio {
    override val nombre = "CONFIRMADA"
    override fun manejar(contexto: SincronizacionRecepcion, evento: Evento) {
        throw TransicionInvalida(nombre, evento)
    }
}

object Conflicto : EstadoEnvio {
    override val nombre = "CONFLICTO"
    override fun manejar(contexto: SincronizacionRecepcion, evento: Evento) {
        when (evento) {
            is Evento.RevisionAplicada -> contexto.cambiar(Confirmada)
            is Evento.DescarteAutorizado -> contexto.cambiar(Descartada)
            else -> throw TransicionInvalida(nombre, evento)
        }
    }
}

object Descartada : EstadoEnvio {
    override val nombre = "DESCARTADA"
    override fun manejar(contexto: SincronizacionRecepcion, evento: Evento) {
        throw TransicionInvalida(nombre, evento)
    }
}

/** El contexto del patrón: una captura y su estado de envío actual. */
class SincronizacionRecepcion(
    val clave: String,
    estadoInicial: EstadoEnvio = Pendiente,
    var recepcionId: String? = null,
    var fechaSincronizacion: String? = null,
    var motivo: String? = null
) {
    var estado: EstadoEnvio = estadoInicial
        private set

    fun manejar(evento: Evento) = estado.manejar(this, evento)

    fun cambiar(nuevo: EstadoEnvio) {
        estado = nuevo
    }

    companion object {
        fun estadoPorNombre(nombre: String): EstadoEnvio = when (nombre) {
            Pendiente.nombre -> Pendiente
            Enviando.nombre -> Enviando
            Confirmada.nombre -> Confirmada
            Conflicto.nombre -> Conflicto
            Descartada.nombre -> Descartada
            else -> throw IllegalArgumentException("Estado desconocido: $nombre")
        }
    }
}

/**
 * Lo que ve el repartidor sobre la entrega. ENTREGADA u OBSERVADA solo aparecen
 * cuando el servidor confirmó la captura: no hay forma de llegar a entregada sin
 * sincronizar. Observada no es un error ni un rechazo: la entrega ocurrió con reparos.
 */
fun estadoVisibleEntrega(estadoEnvio: EstadoEnvio?, conforme: Boolean?): String = when (estadoEnvio) {
    null -> "Disponible para capturar"
    Pendiente -> "Pendiente de sincronizar"
    Enviando -> "Enviando"
    Confirmada -> if (conforme == true) "ENTREGADA" else "OBSERVADA"
    Conflicto -> "En conflicto: esperando revisión"
    Descartada -> "Descartada por revisión"
    else -> estadoEnvio.nombre
}
