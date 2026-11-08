package com.example.vidoora.ui.theme

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

/** Save only numeric positions; a temporary loading row must not overwrite the saved anchor. */
@Composable fun rememberRestoringListState(ready: Boolean, scopeKey: String): LazyListState {
    var index by rememberSaveable(scopeKey) { mutableIntStateOf(0) }
    var offset by rememberSaveable(scopeKey) { mutableIntStateOf(0) }
    val state = rememberLazyListState()
    LaunchedEffect(ready, scopeKey) {
        if (ready) {
            state.scrollToItem(index, offset)
            snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
                .collect { (i, o) -> index = i; offset = o }
        }
    }
    return state
}
