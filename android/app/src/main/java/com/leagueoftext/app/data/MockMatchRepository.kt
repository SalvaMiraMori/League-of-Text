package com.leagueoftext.app.data

import android.os.SystemClock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Partits inventats per a les pestanyes **Pròxims** i **En directe** (captures, vídeos de
 * demostració, proves de la interfície sense backend). S'activa amb `leagueoftext.mockData=true`
 * a `gradle.properties` i només a les compilacions de debug.
 *
 * - Les hores són relatives al moment d'obrir l'app, de manera que sempre hi ha partits a punt
 *   de començar i partits en directe.
 * - Les partides en directe avancen: el rellotge corre i hi van apareixent esdeveniments nous
 *   (kills, dracs, torres, baró...), generats de manera determinista per a cada partida.
 * - **Resultats** i qualsevol partit que no sigui mock es llegeixen de [fallback] (Firestore), i
 *   d'aquelles dades reals s'agafen els logotips dels equips i les lligues amb el mateix codi.
 */
class MockMatchRepository(
    private val fallback: MatchSource?,
    scope: CoroutineScope,
) : MatchSource {

    private val openedAt = SystemClock.elapsedRealtime()
    private val now = Instant.now()

    private val upcoming: List<Match> = MockData.upcoming(now)
    private val liveMatches: List<MockLiveMatch> = MockData.live(now)
    private val liveById = liveMatches.associateBy { it.match.id }
    private val upcomingById = upcoming.associateBy { it.id }

    private val logos: Flow<Logos> =
        fallback?.completed?.map { Logos.from(it.data) }?.distinctUntilChanged() ?: flowOf(Logos())

    override val scheduled: StateFlow<Feed<List<Match>>> = logos
        .map { logo -> Feed(upcoming.map(logo::apply)) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), Feed(upcoming))

    override val live: StateFlow<Feed<List<Match>>> = logos
        .map { logo -> Feed(liveMatches.map { logo.apply(it.match) }) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), Feed(liveMatches.map { it.match }))

    override val completed: StateFlow<Feed<List<Match>>> =
        fallback?.completed ?: MutableStateFlow(Feed(emptyList()))

    override suspend fun refreshLists() {
        delay(REFRESH_DELAY_MS) // Perquè l'indicador d'estirar per refrescar es vegi.
        fallback?.refreshLists()
    }

    override suspend fun refreshMatch(matchId: String, gameId: String?) {
        if (isMock(matchId)) delay(REFRESH_DELAY_MS) else fallback?.refreshMatch(matchId, gameId)
    }

    override fun match(matchId: String): Flow<Match?> {
        val mock = liveById[matchId]?.match ?: upcomingById[matchId]
        return if (mock != null) logos.map { it.apply(mock) } else fallback?.match(matchId) ?: flowOf(null)
    }

    override fun games(matchId: String): Flow<List<Game>> {
        val live = liveById[matchId]
        return when {
            live != null -> ticking { elapsed -> live.games(elapsed) }
            matchId in upcomingById -> flowOf(emptyList())
            else -> fallback?.games(matchId) ?: flowOf(emptyList())
        }
    }

    override fun events(matchId: String, gameId: String): Flow<List<GameEvent>> {
        val live = liveById[matchId]
        return when {
            live != null -> ticking { elapsed -> live.events(gameId, elapsed) }.distinctUntilChanged()
            matchId in upcomingById -> flowOf(emptyList())
            else -> fallback?.events(matchId, gameId) ?: flowOf(emptyList())
        }
    }

    private fun isMock(matchId: String) = matchId in liveById || matchId in upcomingById

    /** Torna a calcular el valor cada segon amb els segons que fa que s'ha obert l'app. */
    private fun <T> ticking(value: (elapsedSeconds: Int) -> T): Flow<T> = flow {
        while (true) {
            emit(value(((SystemClock.elapsedRealtime() - openedAt) / 1000).toInt()))
            delay(1_000)
        }
    }

    /** Logotips d'equips (per codi) i de lligues trets de dades reals. */
    private data class Logos(
        val teams: Map<String, String> = emptyMap(),
        val leagues: Map<String, String> = emptyMap(),
    ) {
        fun apply(match: Match): Match = match.copy(
            teams = match.teams.map { team -> teams[team.code.uppercase()]?.let { team.copy(image = it) } ?: team },
            league = match.league?.let { league ->
                leagues[Leagues.canonicalKey(league)]?.let { league.copy(image = it) } ?: league
            },
        )

        companion object {
            fun from(matches: List<Match>) = Logos(
                teams = matches.flatMap { it.teams }
                    .filter { it.image.isNotBlank() && !it.isTbd }
                    .associate { it.code.uppercase() to it.image },
                leagues = matches.mapNotNull { it.league }
                    .filter { it.image.isNotBlank() }
                    .associate { Leagues.canonicalKey(it) to it.image },
            )
        }
    }

    private companion object {
        const val REFRESH_DELAY_MS = 700L
    }
}

/** Partit en directe: les partides acabades són fixes i la que es juga avança amb el temps. */
private class MockLiveMatch(val match: Match, private val games: List<MockGame>) {

    fun games(elapsed: Int): List<Game> = games.map { it.toGame(elapsed) }

    fun events(gameId: String, elapsed: Int): List<GameEvent> =
        games.firstOrNull { it.id == gameId }?.events(elapsed).orEmpty()
}

private class MockGame(
    val id: String,
    private val number: Int,
    private val blue: Team,
    private val red: Team,
    /** Guanyador si la partida ja s'ha acabat; `null` si s'està jugant. */
    private val winner: Team?,
    /** Durada de la partida acabada, o temps de joc en el moment d'obrir l'app. */
    private val seconds: Int,
    private val timeline: MockTimeline,
) {
    private val finished get() = winner != null

    private fun clock(elapsed: Int) = if (finished) seconds else seconds + elapsed

    fun toGame(elapsed: Int): Game {
        val t = clock(elapsed)
        return Game(
            id = id,
            number = number,
            state = if (finished) GameState.COMPLETED else GameState.IN_PROGRESS,
            winnerId = winner?.id,
            blueTeam = blue.code,
            redTeam = red.code,
            blueTeamId = blue.id,
            redTeamId = red.id,
            playingSeconds = t,
            paused = false,
            stats = timeline.statsAt(t),
        )
    }

    fun events(elapsed: Int): List<GameEvent> {
        val t = clock(elapsed)
        return timeline.events
            .filter { it.seconds <= t }
            .mapIndexed { index, event ->
                GameEvent(
                    id = "$id-$index",
                    order = index,
                    type = EventType.from(event.type, event.text),
                    text = event.text,
                    playingTime = formatClock(event.seconds),
                    side = event.side,
                    blueTeam = blue.code,
                    redTeam = red.code,
                    stats = event.stats,
                )
            }
            .reversed()
    }
}

private data class Counts(
    val kills: Int = 0,
    val towers: Int = 0,
    val inhibitors: Int = 0,
    val barons: Int = 0,
    val dragons: Int = 0,
) {
    fun toStats(seconds: Int) = TeamStats(
        kills = kills,
        gold = 2_500 + seconds * 31 + kills * 300 + towers * 550 + inhibitors * 250 + barons * 1_200 + dragons * 150,
        towers = towers,
        inhibitors = inhibitors,
        barons = barons,
        dragons = dragons,
    )
}

private class MockEvent(
    val seconds: Int,
    val type: String,
    val text: String,
    val side: EventSide?,
    val blue: Counts,
    val red: Counts,
) {
    val stats: GameStats get() = GameStats(blue.toStats(seconds), red.toStats(seconds))
}

/**
 * Cronologia generada d'una partida, minut a minut, amb la mateixa llavor sempre (els
 * esdeveniments no canvien entre lectures). [blueEdge] (-1..1) inclina els combats i els
 * objectius cap a un costat. Els textos imiten els del backend (`backend/src/leagueoftext/game.py`).
 */
private class MockTimeline(
    blue: Team,
    red: Team,
    title: String,
    seed: Long,
    blueEdge: Double,
    /** Final de la partida (segons) i si guanya el blau; `null` si encara s'està jugant. */
    end: Pair<Int, Boolean>?,
) {
    val events: List<MockEvent>

    init {
        val rnd = Random(seed)
        val out = mutableListOf<MockEvent>()
        var b = Counts()
        var r = Counts()
        val pBlue = 0.5 + blueEdge * 0.25 // objectius
        val pBlueKills = 0.5 + blueEdge * 0.15 // combats: més repartits
        val limit = end?.first ?: MAX_SECONDS
        val drakes = DRAGONS.shuffled(rnd)
        var firstBlood = false
        var firstTower = false
        var dragonsTaken = 0
        var nextDragon = 5 * 60
        var soulTaken = false
        var nextElder = Int.MAX_VALUE
        var nextBaron = 25 * 60

        fun name(blueSide: Boolean) = if (blueSide) blue.name else red.name
        fun side(blueSide: Boolean) = if (blueSide) EventSide.BLUE else EventSide.RED
        fun emit(t: Int, type: String, text: String, blueSide: Boolean?) {
            if (t < limit) out += MockEvent(t, type, text, blueSide?.let(::side), b, r)
        }
        fun update(blueSide: Boolean, change: (Counts) -> Counts) {
            if (blueSide) b = change(b) else r = change(r)
        }

        emit(0, "game_start", "${title}has just started!", null)
        for (minute in 2..limit / 60) {
            val t0 = minute * 60

            // Combats (primers 25 s del minut).
            if (minute >= 3 && rnd.nextDouble() < 0.45) {
                val t = t0 + rnd.nextInt(25)
                val blueSide = rnd.nextDouble() < pBlueKills
                val kills = listOf(1, 1, 1, 2, 2, 3).random(rnd)
                update(blueSide) { it.copy(kills = it.kills + kills) }
                when {
                    !firstBlood -> {
                        firstBlood = true
                        emit(t, "first_blood", "${name(blueSide)} drew first blood!", blueSide)
                    }
                    kills == 3 && rnd.nextBoolean() ->
                        emit(t, "multikill", "${name(blueSide)} got a triple kill!", blueSide)
                    kills == 1 -> emit(t, "kill", "${name(blueSide)} got a kill.", blueSide)
                    else -> emit(t, "kill", "${name(blueSide)} got $kills kills.", blueSide)
                }
            }

            // Dracs i baró (del segon 25 al 45).
            val objectiveTime = t0 + 25 + rnd.nextInt(20)
            if (!soulTaken && t0 >= nextDragon) {
                val blueSide = rnd.nextDouble() < pBlue
                val drake = drakes[dragonsTaken.coerceAtMost(2)]
                dragonsTaken++
                nextDragon = t0 + 5 * 60 + 30
                update(blueSide) { it.copy(dragons = it.dragons + 1) }
                emit(objectiveTime, "dragon", "${name(blueSide)} killed a $drake dragon.", blueSide)
                if ((if (blueSide) b else r).dragons == 4) {
                    soulTaken = true
                    nextElder = t0 + 6 * 60
                    emit(objectiveTime, "soul", "${name(blueSide)} got the $drake soul!", blueSide)
                }
            } else if (t0 >= nextElder) {
                val blueSide = rnd.nextDouble() < pBlue
                nextElder = t0 + 6 * 60
                emit(objectiveTime, "elder", "${name(blueSide)} killed the Elder Dragon!", blueSide)
            }
            if (t0 >= nextBaron && rnd.nextDouble() < 0.35) {
                val blueSide = rnd.nextDouble() < pBlue
                nextBaron = t0 + 6 * 60
                update(blueSide) { it.copy(barons = it.barons + 1) }
                emit(objectiveTime + 5, "baron", "${name(blueSide)} killed Baron Nashor!", blueSide)
            }

            // Torres i inhibidors (últims 15 s del minut).
            val structureTime = t0 + 45 + rnd.nextInt(15)
            if (minute >= 12 && rnd.nextDouble() < 0.25 + minute * 0.008) {
                val blueSide = rnd.nextDouble() < pBlue
                if ((if (blueSide) b else r).towers < 11) {
                    update(blueSide) { it.copy(towers = it.towers + 1) }
                    if (!firstTower) {
                        firstTower = true
                        emit(structureTime, "first_tower", "${name(blueSide)} destroyed the first tower!", blueSide)
                    } else {
                        emit(structureTime, "tower", "${name(blueSide)} destroyed a tower.", blueSide)
                    }
                }
            }
            if (minute >= 24 && rnd.nextDouble() < 0.18) {
                val blueSide = rnd.nextDouble() < pBlue
                val own = if (blueSide) b else r
                if (own.towers >= 5 && own.inhibitors < 3) {
                    update(blueSide) { it.copy(inhibitors = it.inhibitors + 1) }
                    emit(structureTime, "inhibitor", "${name(blueSide)} destroyed an inhibitor!", blueSide)
                }
            }
        }
        if (end != null) {
            val (seconds, blueWins) = end
            // El guanyador ha d'haver tirat el nexe: més torres que el rival i almenys un inhibidor.
            val loserTowers = (if (blueWins) r else b).towers
            update(blueWins) {
                it.copy(
                    towers = maxOf(it.towers + 1, loserTowers + 2, 7).coerceAtMost(11),
                    inhibitors = it.inhibitors.coerceAtLeast(1),
                )
            }
            out += MockEvent(seconds, "game_end", "${name(blueWins)} wins the game!", side(blueWins), b, r)
        }
        events = out
    }

    fun statsAt(seconds: Int): GameStats {
        val last = events.lastOrNull { it.seconds <= seconds }
        return GameStats(
            (last?.blue ?: Counts()).toStats(seconds),
            (last?.red ?: Counts()).toStats(seconds),
        )
    }

    private companion object {
        const val MAX_SECONDS = 60 * 60
        val DRAGONS = listOf("Infernal", "Mountain", "Ocean", "Cloud", "Hextech", "Chemtech")
    }
}

private fun formatClock(seconds: Int): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%02d:%02d", m, s)
}

private object MockData {

    private val LCK = League(id = "mock-lck", name = "LCK", slug = "lck", image = "")
    private val LEC = League(id = "mock-lec", name = "LEC", slug = "lec", image = "")
    private val LCS = League(id = "mock-lcs", name = "LCS", slug = "lcs", image = "")
    private val MSI = League(id = "mock-msi", name = "MSI", slug = "msi", image = "")
    private val FIRST_STAND = League(id = "mock-first-stand", name = "First Stand", slug = "first_stand", image = "")

    private fun team(code: String, name: String) =
        Team(id = "mock-${code.lowercase()}", code = code, name = name, image = "")

    private val T1 = team("T1", "T1")
    private val GEN = team("GEN", "Gen.G")
    private val HLE = team("HLE", "Hanwha Life Esports")
    private val KT = team("KT", "KT Rolster")
    private val DK = team("DK", "Dplus KIA")
    private val BFX = team("BFX", "BNK FEARX")
    private val G2 = team("G2", "G2 Esports")
    private val FNC = team("FNC", "Fnatic")
    private val KC = team("KC", "Karmine Corp")
    private val MKOI = team("MKOI", "Movistar KOI")
    private val TH = team("TH", "Team Heretics")
    private val FLY = team("FLY", "FlyQuest")
    private val TL = team("TL", "Team Liquid")
    private val C9 = team("C9", "Cloud9")
    private val BLG = team("BLG", "Bilibili Gaming")
    private val AL = team("AL", "Anyone's Legend")

    fun upcoming(now: Instant): List<Match> {
        val hour = now.truncatedTo(ChronoUnit.HOURS)
        fun match(id: String, league: League, block: String, bestOf: Int, a: Team, b: Team, inHours: Long) = Match(
            id = id,
            state = MatchState.SCHEDULED,
            startTime = hour.plus(inHours, ChronoUnit.HOURS),
            bestOf = bestOf,
            blockName = block,
            league = league,
            teams = listOf(a, b),
            score = emptyMap(),
            winnerId = null,
        )
        return listOf(
            match("mock-up-1", LCK, "Week 4", 3, HLE, KT, 2),
            match("mock-up-2", LCK, "Week 4", 3, DK, BFX, 4),
            match("mock-up-3", LEC, "Playoffs", 5, KC, MKOI, 22),
            match("mock-up-4", LCS, "Week 3", 3, C9, FLY, 27),
            match("mock-up-5", LCK, "Week 4", 3, T1, HLE, 46),
            match("mock-up-6", LEC, "Playoffs", 5, G2, TH, 48),
            match("mock-up-7", LCS, "Week 3", 3, TL, C9, 51),
            match("mock-up-8", MSI, "Bracket Stage", 5, BLG, AL, 96),
            match("mock-up-9", FIRST_STAND, "Final", 5, Team.TBD, Team.TBD, 144),
        )
    }

    /** Especificació d'una partida: costats, guanyador (si s'ha acabat), durada o temps actual i avantatge del blau. */
    private class Spec(val blue: Team, val red: Team, val winner: Team?, val seconds: Int, val blueEdge: Double)

    private fun live(
        id: String,
        league: League,
        block: String,
        bestOf: Int,
        a: Team,
        b: Team,
        startedMinutesAgo: Long,
        now: Instant,
        specs: List<Spec>,
    ): MockLiveMatch {
        val games = specs.mapIndexed { index, spec ->
            val number = index + 1
            val gameNumber = if (bestOf > 1) "game $number " else ""
            MockGame(
                id = "$id-g$number",
                number = number,
                blue = spec.blue,
                red = spec.red,
                winner = spec.winner,
                seconds = spec.seconds,
                timeline = MockTimeline(
                    blue = spec.blue,
                    red = spec.red,
                    title = "${spec.blue.name} vs ${spec.red.name} $gameNumber",
                    seed = (id + number).hashCode().toLong(),
                    blueEdge = spec.blueEdge,
                    end = spec.winner?.let { spec.seconds to (it == spec.blue) },
                ),
            )
        }
        val score = specs.mapNotNull { it.winner }.groupingBy { it.id }.eachCount()
        val match = Match(
            id = id,
            state = MatchState.LIVE,
            startTime = now.minus(startedMinutesAgo, ChronoUnit.MINUTES),
            bestOf = bestOf,
            blockName = block,
            league = league,
            teams = listOf(a, b),
            score = mapOf(a.id to (score[a.id] ?: 0), b.id to (score[b.id] ?: 0)),
            winnerId = null,
        )
        return MockLiveMatch(match, games)
    }

    fun live(now: Instant): List<MockLiveMatch> = listOf(
        // T1 guanya la primera i la segona partida va igualada.
        live(
            "mock-live-1", LCK, "Week 4", 3, T1, GEN, startedMinutesAgo = 62, now = now,
            specs = listOf(
                Spec(T1, GEN, winner = T1, seconds = 31 * 60 + 42, blueEdge = 0.6),
                Spec(GEN, T1, winner = null, seconds = 18 * 60 + 27, blueEdge = 0.05),
            ),
        ),
        // Playoffs al millor de cinc: 2-1 i la quarta partida ja és tard.
        live(
            "mock-live-2", LEC, "Playoffs", 5, G2, FNC, startedMinutesAgo = 150, now = now,
            specs = listOf(
                Spec(G2, FNC, winner = G2, seconds = 28 * 60 + 5, blueEdge = 0.7),
                Spec(FNC, G2, winner = FNC, seconds = 35 * 60 + 51, blueEdge = 0.5),
                Spec(G2, FNC, winner = G2, seconds = 30 * 60 + 18, blueEdge = 0.55),
                Spec(FNC, G2, winner = null, seconds = 27 * 60 + 10, blueEdge = -0.3),
            ),
        ),
        // Acaba de començar: primera partida als primers minuts.
        live(
            "mock-live-3", LCS, "Week 3", 3, FLY, TL, startedMinutesAgo = 9, now = now,
            specs = listOf(
                Spec(FLY, TL, winner = null, seconds = 4 * 60 + 40, blueEdge = 0.2),
            ),
        ),
    )
}
