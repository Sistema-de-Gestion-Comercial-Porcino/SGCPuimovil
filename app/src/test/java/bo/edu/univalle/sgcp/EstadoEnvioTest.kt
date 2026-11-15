package bo.edu.univalle.sgcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Patrón State: transiciones válidas y, sobre todo, las que no lo son. */
class EstadoEnvioTest {
    private fun nueva() = SincronizacionRecepcion("clave-1")

    @Test fun recorridoNormalPendienteEnviandoConfirmada() {
        val s = nueva()
        assertSame(Pendiente, s.estado)
        s.manejar(Evento.Sincronizar)
        assertSame(Enviando, s.estado)
        s.manejar(Evento.RecibirConfirmacion("rec-1", "2026-11-14T20:00:00Z"))
        assertSame(Confirmada, s.estado)
        assertEquals("rec-1", s.recepcionId)
    }

    @Test(expected = TransicionInvalida::class)
    fun noSeLlegaAConfirmadaSinEnviar() {
        nueva().manejar(Evento.RecibirConfirmacion("rec-1", null))
    }

    @Test(expected = TransicionInvalida::class)
    fun pendienteNoPasaDirectoAConflicto() {
        nueva().manejar(Evento.RecibirConflicto("x", null))
    }

    @Test fun sinConexionVuelveAPendiente() {
        val s = nueva()
        s.manejar(Evento.Sincronizar)
        s.manejar(Evento.PerderConexion("sin señal"))
        assertSame(Pendiente, s.estado)
    }

    @Test(expected = TransicionInvalida::class)
    fun unConflictoNoSeReenviaSolo() {
        val s = SincronizacionRecepcion("c", Conflicto)
        s.manejar(Evento.Sincronizar)
    }

    @Test fun conflictoSoloSaleConRevisionDelServidor() {
        val aplicada = SincronizacionRecepcion("a", Conflicto)
        aplicada.manejar(Evento.RevisionAplicada)
        assertSame(Confirmada, aplicada.estado)
        val descartada = SincronizacionRecepcion("d", Conflicto)
        descartada.manejar(Evento.DescarteAutorizado)
        assertSame(Descartada, descartada.estado)
    }

    @Test fun confirmadaYDescartadaSonTerminales() {
        for (terminal in listOf(Confirmada, Descartada)) {
            for (evento in listOf(Evento.Sincronizar, Evento.PerderConexion("x"), Evento.RevisionAplicada, Evento.DescarteAutorizado)) {
                val s = SincronizacionRecepcion("t", terminal)
                runCatching { s.manejar(evento) }
                assertSame(terminal, s.estado)
            }
        }
    }

    @Test fun entregadaUObservadaSoloConCapturaConfirmada() {
        assertEquals("Pendiente de sincronizar", estadoVisibleEntrega(Pendiente, true))
        assertEquals("ENTREGADA", estadoVisibleEntrega(Confirmada, true))
        assertEquals("OBSERVADA", estadoVisibleEntrega(Confirmada, false))
        assertEquals("En conflicto: esperando revisión", estadoVisibleEntrega(Conflicto, true))
    }
}
