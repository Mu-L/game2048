package org.andstatus.game2048.model

import korlibs.io.concurrent.atomic.KorAtomicInt
import korlibs.io.concurrent.atomic.KorAtomicRef
import korlibs.io.concurrent.atomic.korAtomic
import korlibs.time.DateTimeTz
import korlibs.time.weeks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.andstatus.game2048.MyContext
import org.andstatus.game2048.gameIsLoading
import org.andstatus.game2048.keyCurrentGameId
import org.andstatus.game2048.model.GameRecord.Companion.makeGameRecord
import org.andstatus.game2048.myLog
import org.andstatus.game2048.myMeasuredIt
import org.andstatus.game2048.stubGameId
import kotlin.math.max

const val keyGameMode = "gameMode"

/** @author yvolk@yurivolkov.com */
class History(
    val myContext: MyContext
) {
    private val stubGame: GameRecord = GameRecord.newEmpty(myContext, stubGameId).load()
    private val recentGamesRef: KorAtomicRef<List<ShortRecord>> = korAtomic(emptyList())
    val recentGames get() = recentGamesRef.value
    private val currentGameRef: KorAtomicRef<GameRecord> = korAtomic(stubGame)
    val currentGame: GameRecord get() = currentGameRef.value

    // 1. Info on previous games
    private val bestScoreStoredRef: KorAtomicInt = korAtomic(0)
    val bestScore: Int get() = max(currentGame.score, bestScoreStoredRef.value)

    // 2. This game, see for the inspiration https://en.wikipedia.org/wiki/Portable_Game_Notation
    /** 0 means that the pointer is turned off */
    var redoPlyPointer: Int = 0
    val gameMode: GameMode = GameMode().apply {
        modeEnum = GameModeEnum.fromId(myContext.storage.getOrNull(keyGameMode) ?: "").let {
            when (it) {
                GameModeEnum.AI_PLAY, GameModeEnum.PLAY -> GameModeEnum.PLAY
                else -> GameModeEnum.STOP
            }
        }
    }

    companion object {
        suspend fun load(myContext: MyContext): History = coroutineScope {
            History(myContext).also { history ->
                myContext.currentGameId?.let { gameId ->
                    myMeasuredIt("Current game loaded") {
                        history.openGame(gameId)
                    }
                }
            }
        }
    }

    private fun ensureRecentGames(): History = if (recentGames.isEmpty()) loadRecentGames() else this

    fun loadRecentGames(): History {
        myMeasuredIt("Recent games loaded") {
            recentGamesRef.value = myContext.settings.gameIdsRange.fold(emptyList()) { acc, ind ->
                ShortRecord.fromId(myContext, ind)
                    ?.let { acc + it } ?: acc
            }
            "${recentGames.size} records"
        }
        return this
    }

    fun openNewGame(): GameRecord = GameRecord.newEmpty(myContext, idForNewGame()).load().let {
        openGame(it, it.id) ?: throw IllegalStateException("Failed to open new game")
    }

    fun openGame(id: Int): GameRecord? =
        currentGame.let {
            if (it.id == id) return it
            else null
        }
            ?: GameRecord.fromId(myContext, id)
                ?.also { openGame(it, id) }

    fun openGame(gameIn: GameRecord?, id: Int): GameRecord? = gameIn
        ?.also { game ->
            if (game.id == id) {
                myLog("Opened game $game")
            } else {
                myLog("Fixed id $id while opening game $game")
                game.id = id
            }
            currentGameRef.value = game
            myContext.storage[keyCurrentGameId] = game.id
            bestScoreStoredRef.value = myContext.storage.getInt(game.boardSize.keyBest, 0)
            saveBestScore(game)
            gameMode.modeEnum = if (game.isEmpty) GameModeEnum.PLAY else GameModeEnum.STOP
        }
        ?: run {
            myLog("Failed to open game $id")
            null
        }

    fun saveCurrent(coroutineScope: CoroutineScope): History {
        myContext.storage[keyGameMode] = gameMode.modeEnum.id
        currentGame.let { game ->
            myContext.storage[keyCurrentGameId] = game.id

            coroutineScope.launch {
                myMeasuredIt("Game saved") {
                    saveBestScore(game)
                    game.save()
                    gameIsLoading.compareAndSet(expect = true, update = false)
                    game
                }
                loadRecentGames()
            }
        }
        return this
    }

    private fun saveBestScore(game: GameRecord) {
        (game.score).let { score ->
            if (bestScoreStoredRef.value < score) {
                myContext.storage[game.boardSize.keyBest] = score
                bestScoreStoredRef.value = score
            }
        }
    }

    fun idForNewGame(): Int = ensureRecentGames()
        .let { idToDelete() ?: unusedGameId() }
        .also {
            deleteGame(it)
            myLog("idForNewGame: $it")
        }

    private fun idToDelete() = if (recentGames.size > myContext.settings.maxOlderGames) {
        val keepAfter = DateTimeTz.nowLocal().minus(1.weeks)
        val olderGames = recentGames.filterNot {
            it.finalPosition.startingDateTime >= keepAfter || it.id == currentGame.id
        }
        when {
            olderGames.size > 20 -> olderGames.minByOrNull { it.finalPosition.score }?.id
            recentGames.size >= myContext.settings.gameIdsRange.last -> recentGames.minByOrNull { it.finalPosition.score }?.id
            else -> null
        }
    } else null

    private fun unusedGameId() = myContext.settings.gameIdsRange.filterNot { it == currentGame.id }
        .find { id -> recentGames.none { it.id == id } }
        ?: recentGames.filterNot { it.id == currentGame.id }
            .minByOrNull { it.finalPosition.startingDateTime }?.id
        ?: throw IllegalStateException("Failed to find unusedGameId")

    fun deleteCurrent() = deleteGame(currentGame.id)

    private fun deleteGame(id: Int) {
        recentGamesRef.value = recentGames.filterNot { it.id == id }
        if (currentGame.id == id) {
            currentGameRef.value = latestOtherGame(id) ?: stubGame
        }
        GameRecord.delete(myContext, id)
    }

    private fun latestOtherGame(notTheId: Int): GameRecord? = recentGames
        .filterNot { it.id == notTheId }
        .maxByOrNull { it.finalPosition.startingDateTime }
        ?.makeGameRecord()

    val plyToRedo: Ply?
        get() = currentGame.let { game ->
            when {
                redoPlyPointer < 1 || redoPlyPointer > game.gamePlies.size -> null
                else -> game.gamePlies[redoPlyPointer]
            }
        }

    fun add(position: PlyAndPosition) = currentGame.let { game ->
        currentGameRef.value = when (position.ply.plyEnum) {
            PlyEnum.LOAD -> game.replayedAtPosition(position.position)
            else -> {
                val bookmarksNew = when {
                    redoPlyPointer < 1 -> {
                        game.shortRecord.bookmarks
                    }

                    redoPlyPointer == 1 -> {
                        emptyList()
                    }

                    else -> {
                        game.shortRecord.bookmarks.filterNot { it.plyNumber >= redoPlyPointer }
                    }
                }
                val gamePliesNew = when {
                    redoPlyPointer < 1 -> {
                        game.gamePlies
                    }

                    redoPlyPointer == 1 -> {
                        GamePlies(game.shortRecord, emptySequenceLineReader)
                    }

                    else -> {
                        game.gamePlies.take(redoPlyPointer - 1)
                    }
                } + position.ply
                with(game.shortRecord) {
                    GameRecord(
                        ShortRecord(myContext, board, note, id, start, position.position, bookmarksNew),
                        gamePliesNew
                    )
                }
            }
        }
        redoPlyPointer = 0
    }

    fun createBookmark(gamePosition: GamePosition) = currentGame.let { game ->
        currentGameRef.value = with(game.shortRecord) {
            GameRecord(
                ShortRecord(
                    myContext, board, note, id, start, finalPosition,
                    bookmarks.filter { it.plyNumber != gamePosition.plyNumber } +
                        gamePosition.copy()),
                game.gamePlies
            )
        }
    }

    fun deleteBookmark(gamePosition: GamePosition) = currentGame.let { game ->
        currentGameRef.value = with(game.shortRecord) {
            GameRecord(
                ShortRecord(
                    myContext, board, note, id, start, finalPosition, bookmarks
                        .filterNot { it.plyNumber == gamePosition.plyNumber }),
                game.gamePlies
            )
        }
    }

    fun canUndo(): Boolean = currentGame.let { game ->
        myContext.settings.allowUndo &&
            redoPlyPointer != 1 && redoPlyPointer != 2 &&
            game.gamePlies.size > 1 &&
            (redoPlyPointer > 2 || game.gamePlies.lastOrNull()?.player == PlayerEnum.COMPUTER)
    }

    fun undo(): Ply? = currentGame.let { game ->
        if (!canUndo()) {
            return null
        } else if (redoPlyPointer < 1 && game.gamePlies.size > 0) {
            // Point to the last ply
            redoPlyPointer = game.gamePlies.size
        } else if (redoPlyPointer > 1 && redoPlyPointer <= game.gamePlies.size + 1)
            redoPlyPointer--
        else {
            return null
        }
        return plyToRedo
    }

    fun canRedo(): Boolean = currentGame.let { game ->
        return redoPlyPointer > 0 && redoPlyPointer <= game.gamePlies.size
    }

    fun redo(): Ply? = currentGame.let { game ->
        if (canRedo()) {
            return plyToRedo?.also {
                if (redoPlyPointer < game.gamePlies.size)
                    redoPlyPointer++
                else {
                    redoPlyPointer = 0
                }
            }
        }
        redoPlyPointer = 0
        return null
    }

    fun gotoBookmark(position: GamePosition) = currentGame.let { game ->
        redoPlyPointer = if (position.plyNumber >= game.shortRecord.finalPosition.plyNumber) 0
        else position.plyNumber + 1
    }
}
