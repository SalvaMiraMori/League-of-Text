package com.leagueoftext.app.data

/**
 * Competicions que segueix el backend (`DEFAULT_LEAGUES` a `backend/src/leagueoftext/config.py`).
 * Surten sempre a la fila de filtres, encara que la pestanya actual no tingui cap partit seu.
 * El logotip s'agafa del primer partit que en porti; mentrestant es mostren les inicials.
 */
object Leagues {

    val followed: List<League> = listOf(
        League(id = "", name = "LCK", slug = "lck", image = ""),
        League(id = "", name = "LEC", slug = "lec", image = ""),
        League(id = "", name = "LCS", slug = "lcs", image = ""),
        League(id = "", name = "Worlds", slug = "worlds", image = ""),
        League(id = "", name = "MSI", slug = "msi", image = ""),
        League(id = "", name = "First Stand", slug = "first_stand", image = ""),
    )

    private fun normalize(value: String): String =
        value.trim().lowercase().replace(' ', '_').replace('-', '_')

    /** Clau única per filtrar: la de la competició seguida si coincideix per slug o per nom. */
    fun canonicalKey(league: League): String {
        val slug = normalize(league.slug)
        val name = normalize(league.name)
        return followed.firstOrNull { it.slug == slug || normalize(it.name) == name }?.key ?: league.key
    }

    /**
     * Competicions seguides (en l'ordre fix de dalt, amb el logotip si ja se'n coneix) i,
     * al darrere, qualsevol altra lliga que aparegui als partits.
     */
    fun merge(seen: Collection<League>): List<League> {
        val byKey = seen.groupBy(::canonicalKey).mapValues { (_, list) ->
            list.firstOrNull { it.image.isNotBlank() } ?: list.first()
        }
        val fixed = followed.map { base ->
            val known = byKey[base.key]
            if (known == null) base else base.copy(id = known.id, image = known.image)
        }
        val fixedKeys = fixed.map { it.key }.toSet()
        val extra = byKey.filterKeys { it !in fixedKeys }.values.sortedBy { it.name.lowercase() }
        return fixed + extra
    }
}
