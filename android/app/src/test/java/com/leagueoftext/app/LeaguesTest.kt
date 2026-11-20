package com.leagueoftext.app

import com.leagueoftext.app.data.League
import com.leagueoftext.app.data.Leagues
import org.junit.Assert.assertEquals
import org.junit.Test

class LeaguesTest {

    @Test
    fun followedLeaguesAlwaysShown() {
        val keys = Leagues.merge(emptyList()).map { it.key }
        assertEquals(listOf("lck", "lec", "lcs", "worlds", "msi", "first_stand"), keys)
    }

    @Test
    fun matchesByNameAndKeepsLogo() {
        val seen = League(id = "1", name = "First Stand", slug = "", image = "https://logo.png")
        assertEquals("first_stand", Leagues.canonicalKey(seen))
        val merged = Leagues.merge(listOf(seen))
        assertEquals("https://logo.png", merged.first { it.key == "first_stand" }.image)
        assertEquals(6, merged.size)
    }

    @Test
    fun otherLeaguesGoAtTheEnd() {
        val lpl = League(id = "2", name = "LPL", slug = "lpl", image = "")
        assertEquals("lpl", Leagues.merge(listOf(lpl)).last().key)
    }
}
