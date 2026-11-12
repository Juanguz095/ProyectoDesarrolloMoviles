package com.auditlab.common

/** Estado de un módulo de consentimiento por función (plan 0.9, M1). */
object ModuleState {
    const val ACCEPTED = "ACCEPTED"
    const val PAUSED = "PAUSED"
    const val REVOKED = "REVOKED"
    val all = setOf(ACCEPTED, PAUSED, REVOKED)
}

/**
 * Consentimiento independiente por función. Cada módulo tiene su política versionada
 * y se pausa o revoca por separado; retirar el consentimiento general revoca todos.
 * [GALLERY], [LOCATION] y [VIDEO] quedan reservados para las fases M2–M4 del plan 0.9
 * y no son aceptables hasta que sus políticas entren en vigor.
 */
object ConsentModulesPolicy {
    const val VERSION = "consent-modules-1"

    const val BASE = "base"
    const val COMMUNICATIONS = "communications"
    const val NUMBERS = "numbers"
    const val SMS_TEXT = "sms_text"
    const val WHATSAPP = "whatsapp"
    const val SCREEN = "screen"
    const val MANUAL_SHARE = "manual_share"
    const val GALLERY = "gallery"
    const val LOCATION = "location"
    const val VIDEO = "video"

    val policies = linkedMapOf(
        BASE to "base-1",
        COMMUNICATIONS to "communications-1",
        NUMBERS to "numbers-1",
        SMS_TEXT to "sms-text-1",
        WHATSAPP to "whatsapp-preview-2",
        SCREEN to "screen-share-1",
        MANUAL_SHARE to ManualSharePolicy.VERSION,
        GALLERY to "gallery-1",
        LOCATION to "location-1",
        VIDEO to "video-1",
    )

    /** Módulos aceptables hoy; los reservados se habilitan al entrar en vigor sus fases. */
    val available = listOf(BASE, COMMUNICATIONS, NUMBERS, SMS_TEXT, WHATSAPP, SCREEN, MANUAL_SHARE)

    val labels = linkedMapOf(
        BASE to "Batería y conexión",
        COMMUNICATIONS to "SMS y llamadas",
        NUMBERS to "Números de SMS y llamada",
        SMS_TEXT to "Texto de SMS",
        WHATSAPP to "Notificaciones de WhatsApp",
        SCREEN to "Pantalla compartida",
        MANUAL_SHARE to "Texto compartido manualmente",
        GALLERY to "Galería (próximamente)",
        LOCATION to "Ubicación (próximamente)",
        VIDEO to "Video de cámara (próximamente)",
    )

    fun policyOf(module: String): String = policies[module] ?: error("Módulo de consentimiento desconocido.")

    fun validate(module: String, state: String, policyVersion: String) {
        require(module in policies) { "Módulo de consentimiento desconocido." }
        require(state in ModuleState.all) { "Estado de módulo no permitido." }
        require(policyVersion == policies[module]) { "Versión de política del módulo incorrecta." }
    }
}

/** Módulo aceptado, pausado o revocado con su política exacta y fecha de aceptación. */
data class ModuleConsent(
    val module: String,
    val policyVersion: String,
    val state: String,
    val acceptedAt: String,
)
