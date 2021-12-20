import com.soywiz.korge.tests.ViewsForTesting
import com.soywiz.korio.lang.Thread_sleep
import kotlinx.coroutines.delay
import org.andstatus.game2048.model.PlyAndPosition.Companion.allowedRandomPly
import kotlin.test.Test
import kotlin.test.assertEquals

class RetriesTest : ViewsForTesting(log = true) {

    @Test
    fun retriesTest() = myViewsTest(this) {
        val game1 = generateGame(7, 3)
        assertEquals(1, game1.shortRecord.bookmarks.size, currentGameString())

        waitForMainViewShown {
            presenter.onUndoClick()
        }
        waitForMainViewShown {
            presenter.onUndoClick()
        }
        val positionAfterUndos = presenter.model.gamePosition.copy()

        allowedRandomPly(presenter.model.gamePosition).ply.plyEnum.swipeDirection?.let {
            waitForMainViewShown {
                Thread_sleep(2000)
                presenter.onSwipe(it)
            }
        }
        assertEquals(1, presenter.model.gamePosition.retries, currentGameString())

        waitForMainViewShown {
            presenter.onUndoClick()
        }
        presenter.model.history.currentGame.gamePlies.get(presenter.model.history.redoPlyPointer).let { redoPly ->
            assertEquals(1, redoPly.retries, "redoPly: " +
                "$redoPly, " + currentGameString())
        }
        assertEquals(0, presenter.model.gamePosition.retries, currentGameString())

        waitForMainViewShown {
            presenter.onRedoClick()
        }
        assertEquals(1, presenter.model.gamePosition.retries, currentGameString())

        waitForMainViewShown {
            presenter.onUndoClick()
        }
        assertEquals(0, presenter.model.gamePosition.retries, currentGameString())
        // TODO: Time is different.
        //assertEquals(positionAfterUndos.toString(), presenter.model.gamePosition.toString(), currentGameString())
    }

}