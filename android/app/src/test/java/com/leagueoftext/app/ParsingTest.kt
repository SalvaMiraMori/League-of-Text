package com.leagueoftext.app

import com.leagueoftext.app.data.EventType
import com.leagueoftext.app.data.FirestoreParsers
import com.leagueoftext.app.data.MatchState
import com.leagueoftext.app.ui.Formatters
import org.junit.Assert.assertEquals
import org.junit.Test

class ParsingTest {

    @Test
    fun clockToSeconds() {
        assertEquals(754, FirestoreParsers.clockToSeconds("12:34"))
        assertEquals(3754, FirestoreParsers.clockToSeconds("1:02:34"))
        assertEquals(0, FirestoreParsers.clockToSeconds("00:00"))
    }

    @Test
    fun formatsClock() {
        assertEquals("12:34", Formatters.clock(754))
        assertEquals("1:02:34", Formatters.clock(3754))
    }

    @Test
    fun legacyNotificationsGetATypeFromTheirText() {
        assertEquals(EventType.MULTIKILL, EventType.from(null, "Faker got a double kill."))
        assertEquals(EventType.FIRST_TOWER, EventType.from(null, "T1 destroyed the first tower!"))
        assertEquals(EventType.UNPAUSE, EventType.from(null, "The game has been unpaused!"))
        assertEquals(EventType.BARON, EventType.from("baron", "anything"))
    }

    @Test
    fun unknownStateDefaultsToScheduled() {
        assertEquals(MatchState.LIVE, MatchState.from("live"))
        assertEquals(MatchState.SCHEDULED, MatchState.from("whatever"))
    }

    @Test
    fun initials() {
        assertEquals("BG", Formatters.initials("Bilibili Gaming"))
        assertEquals("?", Formatters.initials(""))
    }
}
