package com.leagueoftext.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Color de cada competició, tret del seu logotip. Es fa servir als punts del calendari.
 * Les claus són les de [com.leagueoftext.app.data.Leagues.canonicalKey].
 */
object LeagueColors {

    private val byKey = mapOf(
        "lec" to Color(0xFF00E4BA),          // Verd aigua del logo de la LEC
        "lck" to Color(0xFFE4E4EE),          // El logo és gairebé negre: es fa servir el gris clar del fons perquè es vegi sobre el tema fosc
        "lcs" to Color(0xFF6050F0),          // Lila del fons del logo de la LCS
        "msi" to Color(0xFFDE302A),          // Vermell del logo del MSI
        "worlds" to Color(0xFFD4B46A),       // Daurat del logo de League of Legends
        "first_stand" to Color(0xFFFC5400),  // Taronja del logo de First Stand
    )

    /** Color de la competició, o gris si no en té cap d'assignat. */
    fun forKey(key: String?): Color = byKey[key] ?: RiotColors.TextSecondary
}
