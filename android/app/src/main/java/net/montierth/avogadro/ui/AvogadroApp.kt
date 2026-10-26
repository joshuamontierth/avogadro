package net.montierth.avogadro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

@Composable
fun AvogadroApp(vm: SearchViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var field by remember { mutableStateOf<Rect?>(null) }
    var fieldFocused by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<Int?>(null) }

    val open: (String) -> Unit = { url -> Format.safeUrl(url)?.let { Links.open(context, it, p) } }
    val submit: (String) -> Unit = { q ->
        focusManager.clearFocus()
        keyboard?.hide()
        vm.search(q)
    }

    // The home-screen widget asks for the field: go home and focus it.
    LaunchedEffect(vm.focusRequests) {
        if (vm.focusRequests > 0) {
            viewer = null
            if (vm.screen == Screen.Results) vm.home()
            withFrameNanos { } // the home field exists only after the next frame
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    BackHandler(enabled = vm.screen == Screen.Results && viewer == null) { vm.home() }

    Box(Modifier.fillMaxSize().background(p.felt)) {
        ElectronCloud(
            home = vm.screen == Screen.Home,
            nucleus = field?.center?.takeIf { vm.screen == Screen.Home },
            excitations = vm.excitations,
            modifier = Modifier.fillMaxSize(),
        )

        if (vm.screen == Screen.Home) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(BiasAlignment(0f, -0.22f)).padding(horizontal = 16.dp).widthIn(max = 640.dp),
                ) {
                    Text("Avogadro", style = type(54.sp, 230, p.vapor, tracking = (-0.035).em))
                    Spacer(Modifier.height(26.dp))
                    SearchField(
                        value = vm.text, onValueChange = vm::edit, onSearch = submit,
                        onClear = { vm.edit(androidx.compose.ui.text.input.TextFieldValue("")); focus.requestFocus() },
                        large = true, focus = focus, onBounds = { field = it }, onFocused = { fieldFocused = it },
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxWidth().background(p.felt).statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
                    SearchField(
                        value = vm.text, onValueChange = vm::edit, onSearch = submit,
                        onClear = { vm.edit(androidx.compose.ui.text.input.TextFieldValue("")); focus.requestFocus() },
                        large = false, focus = focus, onBounds = { field = it }, onFocused = { fieldFocused = it },
                    )
                    Tabs(vm.results.category) { vm.setCategory(it) }
                }
                LightEdge(vm.results.loading || vm.results.loadingMore)
                Box(Modifier.weight(1f)) {
                    val bottom = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
                    ResultsBody(vm, bottom, open) { viewer = it }
                    LightSpill()
                }
            }
        }

        val words = vm.suggestions
        val at = field
        if (fieldFocused && words.isNotEmpty() && at != null) {
            SuggestionList(words, vm.text.text, at) { submit(it) }
        }

        viewer?.let { start ->
            ImageViewer(imageHits(vm.results.hits), start, open) { viewer = null }
        }
    }
}
