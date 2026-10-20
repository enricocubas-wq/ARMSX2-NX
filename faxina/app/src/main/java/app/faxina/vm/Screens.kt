package app.faxina.vm

import app.faxina.core.GroupId

enum class ReviewFilter { ALL, MESSENGERS }

sealed interface Screen {
    data object Home : Screen
    data class Scan(val then: Screen?) : Screen
    data class Review(val filter: ReviewFilter = ReviewFilter.ALL) : Screen
    data class Group(val id: GroupId) : Screen
    data object Done : Screen

    /** path vazio = lista de armazenamentos; senão, a pasta aberta. */
    data class Explorer(val path: String = "") : Screen
    data object Apps : Screen
    data object Mail : Screen
}
