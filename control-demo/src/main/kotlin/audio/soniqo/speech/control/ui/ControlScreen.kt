package audio.soniqo.speech.control.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import audio.soniqo.speech.control.ControlUiState
import audio.soniqo.speech.control.FeedItem
import audio.soniqo.speech.control.MicState
import audio.soniqo.speech.control.SystemNote
import audio.soniqo.speech.control.Turn

/** Callbacks the hosting Activity wires to the pipeline/agent. */
data class ControlActions(
    val onMicTap: () -> Unit,
    val onOpenType: () -> Unit,
    val onSubmitTyped: (String) -> Unit,
    val onDismissType: () -> Unit,
    val onOpenInfo: () -> Unit,
    val onDismissInfo: () -> Unit,
)

@Composable
fun ControlScreen(
    state: ControlUiState,
    actions: ControlActions,
) {
    // Respect the system "remove animations" accessibility setting.
    val context = androidx.compose.ui.platform.LocalContext.current
    val reduceMotion = remember {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Header(state, actions)
        Chat(
            state.feed, state.downloadPercent != null, state.downloadStage,
            state.downloadDetail, Modifier.weight(1f),
        )
        OrbDock(state, actions, reduceMotion)
        StatusLine(state)
    }

    if (state.showTypeDialog) {
        TypeCommandDialog(actions.onSubmitTyped, actions.onDismissType)
    }
    if (state.showInfoDialog) {
        InfoDialog(actions.onDismissInfo)
    }
}

// ---------------------------------------------------------------------------
// Header — wordmark, state word, one instrumentation line.
// ---------------------------------------------------------------------------

@Composable
private fun Header(state: ControlUiState, actions: ControlActions) {
    Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SONIQO", color = Foreground, fontFamily = Grotesk,
                fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 3.sp)
            Text(" CONTROL", color = Primary, fontFamily = Grotesk,
                fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f))
            Text("ⓘ", color = MutedFg, fontSize = 16.sp,
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { actions.onOpenInfo() })
        }
        // Status on its own line so a long "downloading …" never collides
        // with the wordmark.
        Spacer(Modifier.height(6.dp))
        Text(state.status, color = MutedFg, fontFamily = Plex, fontSize = 11.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        state.downloadPercent?.let { pct ->
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = Primary, trackColor = MutedSurface,
            )
        }
    }
}

/** Footer: `1397 MB · peak 1423 · stt 67ms · llm 663ms · tts 1486ms · round 2175ms` */
@Composable
private fun StatusLine(state: ControlUiState) {
    val parts = buildList {
        if (state.memNowMb > 0) {
            add("${state.memNowMb} MB")
            add("peak ${state.memPeakMb}")
        }
        state.lastMetrics?.let { m ->
            add("stt ${m.sttMs.toInt()}ms")
            add("llm ${m.llmMs}ms")
            add("tts ${m.ttsMs}ms")
            add("round ${m.roundMs}ms")
        }
    }
    Text(
        if (parts.isEmpty()) "starting up" else parts.joinToString(" · "),
        color = FaintFg, fontFamily = Plex, fontSize = 9.sp, letterSpacing = 0.sp,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

// ---------------------------------------------------------------------------
// Chat — transcribed phrases (right) and recognized commands + replies (left).
// ---------------------------------------------------------------------------

@Composable
private fun Chat(
    feed: List<FeedItem>,
    setupVisible: Boolean,
    setupStage: String?,
    setupDetail: String?,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(feed.lastOrNull()?.id, feed.size) {
        if (feed.isNotEmpty()) listState.animateScrollToItem(feed.size - 1)
    }
    Box(modifier.fillMaxWidth()) {
        if (setupVisible) {
            SetupPanel(setupStage, setupDetail, Modifier.align(Alignment.Center))
        } else if (feed.none { it is Turn }) {
            EmptyHint(Modifier.align(Alignment.Center))
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 18.dp),
            state = listState,
            contentPadding = PaddingValues(top = 6.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(feed, key = { it.id }) { item ->
                AnimatedVisibility(
                    visibleState = remember {
                        MutableTransitionState(false).apply { targetState = true }
                    },
                    enter = fadeIn(tween(240)) + slideInVertically(tween(240)) { it / 3 },
                ) {
                    when (item) {
                        is SystemNote -> Text(item.text, color = FaintFg, fontFamily = Plex,
                            fontSize = 10.5.sp, lineHeight = 15.sp,
                            modifier = Modifier.padding(horizontal = 2.dp))
                        is Turn -> TurnBlock(item)
                    }
                }
            }
        }
    }
}

/**
 * First-run setup panel — the model download (~600 MB, one time). Progress
 * is shown as one dot per model, filling as each finishes, rather than raw
 * filenames, plus a bytes/rate/ETA line. The bundle downloads in a foreground
 * worker, so it continues while the app is backgrounded.
 */
@Composable
private fun SetupPanel(
    currentStage: String?,
    detail: String?,
    modifier: Modifier = Modifier,
) {
    // Download order = dot order. Each model is one dot.
    val models = listOf(
        "voice detection", "transcription model", "speech synthesis", "language model",
    )
    val current = models.indexOf(currentStage).let { if (it < 0) 0 else it }

    val pulse = rememberInfiniteTransition(label = "setup")
    val activeAlpha by pulse.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            tween(700), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "activeAlpha",
    )

    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Setting up", color = Foreground, fontFamily = Grotesk,
            fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text("Downloading the models — about 600 MB, one time. Everything runs " +
            "on your phone and the download continues if you leave.",
            color = MutedFg, fontFamily = Grotesk, fontSize = 13.5.sp, lineHeight = 19.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            models.indices.forEach { i ->
                val color = when {
                    i < current -> Primary                          // downloaded
                    i == current -> Primary.copy(alpha = activeAlpha) // in progress
                    else -> Border                                  // pending
                }
                Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("${current + 1} of ${models.size}", color = FaintFg, fontFamily = Plex,
            fontSize = 11.sp)
        // Bytes, rate and ETA. The dots alone can't distinguish a slow link
        // from a stalled one — the ~290 MB LLM bundle sits inside a single dot
        // for minutes.
        detail?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = FaintFg, fontFamily = Plex, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Say something like", color = FaintFg, fontFamily = Grotesk, fontSize = 14.sp)
        Spacer(Modifier.height(6.dp))
        Text("“call anna” · “play some music” · “set volume to 3”",
            color = MutedFg, fontFamily = Grotesk, fontSize = 14.sp)
    }
}

@Composable
private fun TurnBlock(turn: Turn) {
    Column(Modifier.fillMaxWidth()) {
        // What the pipeline heard — the user's side.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(
                turn.utterance,
                color = Foreground, fontFamily = Grotesk,
                fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                    .background(MutedSurface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        // What the agent recognized + said back.
        if (turn.toolLabel != null || turn.spoken != null) {
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
                    .background(Card)
                    .border(1.dp, Border, RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                turn.toolLabel?.let { label ->
                    Text(label,
                        color = if (turn.failed) Destructive else Primary,
                        fontFamily = Plex, fontSize = 12.sp)
                }
                turn.spoken?.let { spoken ->
                    if (turn.toolLabel != null) Spacer(Modifier.height(6.dp))
                    Text(spoken, color = Foreground, fontFamily = Grotesk,
                        fontSize = 15.sp, lineHeight = 21.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Orb dock.
// ---------------------------------------------------------------------------

@Composable
private fun OrbDock(state: ControlUiState, actions: ControlActions, reduceMotion: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(116.dp)
                .clip(CircleShape)
                .then(
                    if (state.micState == MicState.OFFLINE) Modifier
                    else Modifier.clickable(
                        onClick = actions.onMicTap,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            TalkOrb(state.micState, state.micLevel, reduceMotion = reduceMotion)
        }
        Spacer(Modifier.height(6.dp))
        Text(orbCaption(state.micState), color = MutedFg, fontFamily = Plex, fontSize = 12.sp,
            letterSpacing = 1.sp)
        if (state.micState != MicState.OFFLINE) {
            Spacer(Modifier.height(2.dp))
            Text("hold to type", color = FaintFg, fontFamily = Plex, fontSize = 10.sp,
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { actions.onOpenType() })
        }
    }
}

@Composable
private fun InfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = {
            Row {
                Text("SONIQO", color = Foreground, fontFamily = Grotesk,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 2.sp)
                Text(" CONTROL", color = Primary, fontFamily = Grotesk,
                    fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 2.sp)
            }
        },
        text = {
            Column(
                Modifier.verticalScroll(
                    androidx.compose.foundation.rememberScrollState()),
            ) {
                Text("Say it naturally — the on-device model maps your phrase " +
                    "to one of these commands:",
                    color = Foreground, fontFamily = Grotesk, fontSize = 14.sp,
                    lineHeight = 20.sp)
                Spacer(Modifier.height(14.dp))

                // Rendered straight from the registered tool declarations —
                // this list is exactly what the agent can react to.
                audio.soniqo.speech.control.ControlTools.declarations.forEach { tool ->
                    CapabilityRow(tool)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Primary, fontFamily = Grotesk) }
        },
    )
}

@Composable
private fun CapabilityRow(tool: audio.soniqo.speech.llm.FunctionDeclaration) {
    @Suppress("UNCHECKED_CAST")
    val properties = (tool.parameters["properties"] as? Map<String, Any?>).orEmpty()
    @Suppress("UNCHECKED_CAST")
    val required = (tool.parameters["required"] as? List<String>).orEmpty()
    // `say` is the internal spoken-reply argument, not a user-facing input.
    val signature = properties.keys
        .filter { it != "say" }
        .joinToString(", ") { if (it in required) it else "$it?" }

    Column(Modifier.padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(tool.name, color = Primary, fontFamily = Plex,
                fontWeight = FontWeight.Medium, fontSize = 12.sp)
            if (signature.isNotEmpty()) {
                Text(" ($signature)", color = FaintFg, fontFamily = Plex, fontSize = 11.sp)
            }
        }
        Text(tool.description, color = MutedFg, fontFamily = Grotesk,
            fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun TypeCommandDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("Type a command", color = Foreground, fontFamily = Grotesk) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = { Text("call anna · play some music",
                    color = FaintFg, fontFamily = Plex, fontSize = 13.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    if (text.isNotBlank()) onSubmit(text.trim())
                }),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onSubmit(text.trim()) }) {
                Text("Run", color = Primary, fontFamily = Grotesk)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = MutedFg, fontFamily = Grotesk) }
        },
    )
}
