import com.soywiz.korge.tests.ViewsForTesting
import com.soywiz.korio.concurrent.atomic.korAtomic
import org.andstatus.game2048.model.parseJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

class MultiPageTest : ViewsForTesting(log = true) {

    @Test
    fun multiPageTest() {
        val testWasExecuted = korAtomic(false)
        viewsTest {
            unsetGameView()
            initializeViewDataInTest {
                val pliesPerPage = 6
                val expectedPages = 3
                val expectedPliesCount = pliesPerPage * expectedPages - 3
                presenter.model.settings.pliesPageSize = pliesPerPage
                generateGame(expectedPliesCount)

                val game1 = presenter.model.history.currentGame
                assertEquals(expectedPages, game1.gamePlies.lastPage.pageNumber,
                    "Generated game pages ${currentGameString()}")

                if (presenter.model.history.recentGames.none { it.id == game1.id }) {
                    generateGame(expectedPliesCount + 3)
                }
                presenter.model.history.recentGames.first { it.id != game1.id }.let { shortRecord ->
                    waitForMainViewShown {
                        presenter.onHistoryItemClick(shortRecord.id)
                    }
                }

                val headerJson = presenter.model.history.settings.storage.getOrNull("pliesHead" + game1.id)
                    ?.parseJsonArray()
                assertEquals(expectedPages, headerJson?.size, "Generated game pages $game1")

                waitForMainViewShown {
                    presenter.onHistoryItemClick(game1.id)
                }
                waitFor("Game ${game1.shortRecord} opened") {
                    presenter.model.history.currentGame.id == game1.id
                }
                val game2 = presenter.model.history.currentGame
                assertEquals(expectedPages, game2.gamePlies.lastPage.pageNumber,
                    "Restored from id ${currentGameString()}")

                testWasExecuted.value = true
            }
        }
        waitFor("MultiPageTest was executed") { testWasExecuted.value }
    }
}