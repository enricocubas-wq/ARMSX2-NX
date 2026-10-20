package app.faxina.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.faxina.vm.MailViewModel
import app.faxina.vm.MainViewModel
import app.faxina.vm.Screen
import kotlinx.coroutines.delay

@Composable
fun FaxinaRoot(vm: MainViewModel, mail: MailViewModel) {
    val granted by vm.storageGranted.collectAsStateWithLifecycle()
    val stack by vm.stack.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshPermissions() }
    BackHandler(enabled = granted && stack.size > 1) { vm.back() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (!granted) {
            PermissionScreen(vm)
        } else {
            AnimatedContent(
                targetState = stack.last(),
                transitionSpec = {
                    if (vm.forward) {
                        (slideInHorizontally(tween(280)) { it / 5 } + fadeIn(tween(280))) togetherWith
                            (slideOutHorizontally(tween(280)) { -it / 8 } + fadeOut(tween(180)))
                    } else {
                        (slideInHorizontally(tween(280)) { -it / 8 } + fadeIn(tween(280))) togetherWith
                            (slideOutHorizontally(tween(280)) { it / 5 } + fadeOut(tween(180)))
                    }
                },
                label = "screens",
            ) { screen ->
                when (screen) {
                    Screen.Home -> HomeScreen(vm)
                    is Screen.Scan -> ScanScreen(vm, screen.then)
                    is Screen.Review -> ReviewScreen(vm, screen.filter)
                    is Screen.Group -> GroupScreen(vm, screen.id)
                    Screen.Done -> DoneScreen(vm)
                    is Screen.Explorer -> ExplorerScreen(vm, screen.path)
                    Screen.Apps -> AppsScreen(vm)
                    Screen.Mail -> MailScreen(vm, mail)
                }
            }

            LaunchedEffect(toast) {
                if (toast != null) {
                    delay(2600)
                    vm.dismissToast()
                }
            }
            AnimatedVisibility(
                visible = toast != null,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp),
                enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
                exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
            ) {
                Text(
                    toast ?: "",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { vm.dismissToast() }
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
        }
    }
}
