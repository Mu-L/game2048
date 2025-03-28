import korlibs.io.async.AsyncEntryPointResult
import korlibs.io.async.DEFAULT_SUSPEND_TEST_TIMEOUT
import korlibs.io.async.runBlockingNoJs
import korlibs.io.async.suspendTest
import korlibs.io.async.withTimeoutNullable
import korlibs.io.concurrent.atomic.korAtomic
import korlibs.korge.KorgeConfig
import korlibs.korge.KorgeRunner
import korlibs.korge.internal.KorgeInternal
import korlibs.korge.tests.ViewsForTesting
import korlibs.korge.view.Stage
import korlibs.platform.Platform
import korlibs.time.NIL
import korlibs.time.Stopwatch
import korlibs.time.TimeSpan
import korlibs.time.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.andstatus.game2048.MyContext
import org.andstatus.game2048.gameIsLoading
import org.andstatus.game2048.isTestRun
import org.andstatus.game2048.model.GamePlies
import org.andstatus.game2048.model.GamePosition
import org.andstatus.game2048.model.GameRecord
import org.andstatus.game2048.model.Ply
import org.andstatus.game2048.model.PlyAndPosition.Companion.allowedRandomPly
import org.andstatus.game2048.model.ShortRecord
import org.andstatus.game2048.model.Square
import org.andstatus.game2048.myLog
import org.andstatus.game2048.view.ViewData
import org.andstatus.game2048.view.viewData
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

fun ViewsForTesting.myViewsTest(testObject: Any, block: suspend ViewData.() -> Unit = {}) {
    if (isTestRun.compareAndSet(false, true)) {
        myLog("isTestRun was set")
    }

    val testWasExecuted = korAtomic(false)
    runBlockingNoJs {
        myLog("Test $testObject started")
        viewsTest2(timeout = TimeSpan(60000.0)) {
            viewData(stage).run {
                myLog("Initialized in test")
                waitForMainViewShown(false)
                block()
                testWasExecuted.value = true
                myLog("initializeViewDataInTest after 'viewData' function ended")
            }
        }
        waitFor("Test $testObject was executed") { testWasExecuted.value }
    }
}

/** Copied ViewsForTesting.viewsTest in order to fix it...
 */
@OptIn(KorgeInternal::class)
fun ViewsForTesting.viewsTest2(
    timeout: TimeSpan? = DEFAULT_SUSPEND_TEST_TIMEOUT,
    frameTime: TimeSpan = this.frameTime,
    cond: () -> Boolean = { Platform.isJvm && !Platform.isAndroid },
    forceRenderEveryFrame: Boolean = true,
    block: suspend Stage.() -> Unit
): AsyncEntryPointResult = suspendTest(timeout = timeout, cond = cond) {
    viewsLog.init()
    //suspendTest(timeout = timeout, cond = { !OS.isAndroid && !OS.isJs && !OS.isNative }) {
    KorgeRunner.prepareViewsBase(
        views,
        gameWindow,
        fixedSizeStep = frameTime,
        forceRenderEveryFrame = forceRenderEveryFrame
    )

    injector.mapInstance<KorgeConfig>(
        KorgeConfig(
            title = "KorgeViewsForTesting",
            windowSize = this@viewsTest2.windowSize,
            virtualSize = this@viewsTest2.virtualSize,
        )
    )

    var completed = false
    var completedException: Throwable? = null

    views.launch {
        try {
            block(views.stage)
        } catch (e: Throwable) {
            completedException = e
        } finally {
            completed = true
        }
    }

    withTimeoutNullable(timeout ?: TimeSpan.NIL) {
        while (!completed) {
            delayFrame() //simulateFrame() is private
            delay(10)
            dispatcher.executePending(availableTime = 1.seconds)
        }
        if (completedException != null) throw completedException!!
    }
}

fun ViewData.presentedPieces() = presenter.boardViews.blocksOnBoard.map { it.firstOrNull()?.piece }

fun ViewData.blocksAt(square: Square) = presenter.boardViews.getAll(square).map { it.piece }

fun ViewData.modelAndViews() =
    "Model:     " + presenter.model.gamePosition.pieces.mapIndexed { ind, piece ->
        ind.toString() + ":" + (piece?.text ?: "-")
    } +
        (if (presenter.model.history.currentGame.shortRecord.bookmarks.isNotEmpty())
            "  bookmarks: " + presenter.model.history.currentGame.shortRecord.bookmarks.size
        else "") +
        "\n" +
        "BoardViews:" + presenter.boardViews.blocksOnBoard.mapIndexed { ind, list ->
        ind.toString() + ":" + (if (list.isEmpty()) "-" else list.joinToString(transform = { it.piece.text }))
    }

fun ViewData.currentGameString(): String = "CurrentGame " + presenter.model.history.currentGame.toLongString()

fun ViewData.historyString(): String = with(presenter.model.history) {
    "History: index:$redoPlyPointer, moves:${currentGame.gamePlies.size}"
}

suspend fun ViewData.waitForNextPresented(action: suspend () -> Any? = { null }) {
    val counter1 = presenter.presentedCounter.value
    action()
    waitFor("Next presented after $counter1") {
        counter1 < presenter.presentedCounter.value &&
            presenter.mainViewShown.value && !presenter.isPresenting.value && !gameIsLoading.value
    }
}

suspend fun ViewData.waitForMainViewShown(reset: Boolean = true, action: suspend () -> Any? = {}) {
    if (reset) {
        presenter.mainViewShown.value = false
    }
    action()
    waitFor("Main view shown") {
        presenter.mainViewShown.value && !presenter.isPresenting.value && !gameIsLoading.value
    }
}

suspend fun waitFor(message: String = "???", condition: () -> Boolean) {
    val stopWatch = Stopwatch().start()
    var nextLoggingAt = 1.0
    while (stopWatch.elapsed.seconds < 300) {
        if (nextLoggingAt < stopWatch.elapsed.seconds) {
            myLog("Waiting for: $message")
            nextLoggingAt = stopWatch.elapsed.seconds + 1
        }
        delay(20)
        if (condition()) {
            myLog("Success waiting for: $message")
            return
        }
    }
    throw AssertionError("Condition wasn't met after timeout: $message")
}

fun newGameRecord(
    myContext: MyContext, position: GamePosition, id: Int, bookmarks: List<GamePosition>,
    plies: List<Ply>
) = ShortRecord(myContext, position.board, "", id, position.startingDateTime, position, bookmarks)
    .let {
        GameRecord(it, GamePlies.fromPlies(it, plies))
    }

suspend fun ViewData.generateGame(expectedPliesCount: Int, bookmarkOnPly: Int? = null): GameRecord {
    waitForNextPresented {
        presenter.onTryAgainClick()
    }

    var iteration = 0
    while (iteration < expectedPliesCount) {
        myLog("Iteration $iteration, current ply number: ${presenter.model.gamePosition.plyNumber}, pliesPageSize: ${presenter.view.myContext.settings.pliesPageSize}")
        if (presenter.model.gamePosition.plyNumber >= expectedPliesCount) break

        bookmarkOnPly?.let { plyNumber ->
            if (plyNumber == presenter.model.gamePosition.plyNumber) {
                waitForMainViewShown {
                    presenter.onBookmarkClick()
                }
            }
        }
        allowedRandomPly(presenter.model.gamePosition).let { plyAndPosition ->
            plyAndPosition.ply.plyEnum.swipeDirection?.let {
                myLog("Iteration $iteration of $expectedPliesCount, $it")
                waitForNextPresented {
                    presenter.onSwipe(it)
                }
            } ?: {
                myLog("Iteration $iteration, no swipe for $plyAndPosition, prevPosition: ${presenter.model.gamePosition}")
            }
        }
        iteration++
    }
    val message = "Failed to generate game with $expectedPliesCount plies. ${currentGameString()}"
    assertEquals(expectedPliesCount, presenter.model.gamePosition.plyNumber, message)
    assertEquals(expectedPliesCount, presenter.model.history.currentGame.gamePlies.size, message)
    assertNotNull(presenter.model.history.currentGame.gamePlies.get(expectedPliesCount), message)

    waitForMainViewShown {
        presenter.onPauseClick()
    }

    val game = presenter.model.history.currentGame
    waitFor("Recent games reloaded with gameId:${game.id}") {
        presenter.model.history.recentGames.any { it.id == game.id }
    }
    return game
}

fun Sequence<String>.toTextLines(): String = fold(StringBuilder()) { acc, str ->
    acc.append(str).append("\n")
}.toString()
