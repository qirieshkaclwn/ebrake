package xyz.imd4n.ebrake.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import xyz.imd4n.ebrake.R
import xyz.imd4n.ebrake.transcode.EncoderCatalog
import xyz.imd4n.ebrake.transcode.TranscodeEngine
import xyz.imd4n.ebrake.transcode.TranscodeViewModel

/**
 * Онбординг из трёх экранов:
 * 0 — поток от ввода до вывода (что вообще делает приложение);
 * 1 — папка вывода (по умолчанию Movies/eBrake, можно свою через SAF);
 * 2 — разрешения (медиа + уведомления).
 *
 * Всё необязательное пропускается кнопкой «Далее»: галерея умеет ручной
 * SAF-пикер без разрешений, а кодирование — работать без уведомлений
 * (сервис foreground, просто без видимого прогресса).
 */
@Composable
fun OnboardingScreen(vm: TranscodeViewModel, onDone: () -> Unit) {
    val state by vm.ui.collectAsState()
    var page by rememberSaveable { mutableStateOf(0) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) vm.setOutputTree(uri) }

    // Surface задаёт LocalContentColor = onBackground: голый Column без
    // него оставляет дефолтный чёрный, и заголовки без явного color
    // рисуются чёрным по тёмному фону (карточки не страдали — Card сам
    // выставляет светлый content color).
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            // edge-to-edge: без инсетов заголовок уезжает под строку
            // состояния, а «Далее» — под жест навигации.
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            // Заголовки — Unbounded через MaterialTheme (см. Type.kt): здесь
            // ничего особенного, шрифт применяется сам ко всем headline/title.
            when (page) {
                0 -> FlowPage(Modifier.weight(1f))
                1 -> FolderPage(
                    outputLabel = state.outputLabel,
                    outputTreeChosen = state.outputTreeChosen,
                    onPick = { folderPicker.launch(null) },
                    onReset = { vm.setOutputTree(null) },
                    vm = vm,
                    modifier = Modifier.weight(1f),
                )
                else -> PermissionsPage(Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            BottomBar(
                page = page,
                onBack = { if (page > 0) page-- },
                onNext = { if (page < 2) page++ else onDone() },
            )
        }
    }
}

/** Экран 1: поток Ввод → Правка → Вывод одной картинкой. */
@Composable
private fun FlowPage(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.onboarding_flow_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.onboarding_flow_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        FlowStep(
            icon = Icons.Filled.VideoLibrary,
            title = stringResource(R.string.tab_input),
            text = stringResource(R.string.onboarding_step_input_text),
        )
        FlowStep(
            icon = Icons.Filled.Tune,
            title = stringResource(R.string.tab_edit),
            text = stringResource(R.string.onboarding_step_edit_text),
        )
        FlowStep(
            icon = Icons.Filled.Terminal,
            title = stringResource(R.string.tab_output),
            text = stringResource(R.string.onboarding_step_output_text),
        )
    }
}

@Composable
private fun FlowStep(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    text: String,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Экран 2: куда складывать готовые видео. */
@Composable
private fun FolderPage(
    outputLabel: String,
    outputTreeChosen: Boolean,
    onPick: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    vm: TranscodeViewModel,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.onboarding_folder_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.onboarding_folder_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_folder_current, outputLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPick, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.onboarding_action_choose))
                    }
                }
                if (outputTreeChosen) {
                    OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.onboarding_action_default))
                    }
                }
            }
        }
        // Плашка движков: что этот телефон реально умеет. Пользователь видит
        // сразу, без захода в «Правку», и не удивляется серым пунктам там.
        val encState by vm.ui.collectAsState()
        val available = remember(encState.encodersDetected, encState.availableEncoders) {
            TranscodeEngine.entries.filter { EncoderCatalog.available(it) }
        }
        // Через map: joinToString не пропускает stringResource в transform.
        val availableNames = available.map { stringResource(it.shortNameRes) }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_engines_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (!encState.encodersDetected) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.onboarding_engines_checking),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = availableNames.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/**
 * Экран 3: разрешения.
 *
 * Медиа нужно только для MediaStore-галереи (ручной SAF-пикер и выбранная
 * папка работают без него). Уведомления — только для видимого прогресса:
 * сервис закодирует и без них. Поэтому обе карточки можно пропустить.
 */
@Composable
private fun PermissionsPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Медиа: на 13+ granular READ_MEDIA_VIDEO, ниже старое чтение.
    val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var hasMedia by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, mediaPermission) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val mediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasMedia = granted }

    // Уведомления: runtime-разрешение только на 33+, ниже — всегда есть.
    val needsNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var hasNotif by remember {
        mutableStateOf(
            if (!needsNotif) true else ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    // Возврат из настроек: перепроверяем оба разрешения.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                hasMedia = ContextCompat.checkSelfPermission(context, mediaPermission) ==
                    PackageManager.PERMISSION_GRANTED
                hasNotif = if (!needsNotif) true else ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasNotif = granted }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.onboarding_perm_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.onboarding_perm_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PermissionCard(
            title = stringResource(R.string.onboarding_perm_media_title),
            text = stringResource(R.string.onboarding_perm_media_text),
            granted = hasMedia,
            onGrant = { mediaLauncher.launch(mediaPermission) },
        )
        if (needsNotif) {
            PermissionCard(
                title = stringResource(R.string.onboarding_perm_notif_title),
                text = stringResource(R.string.onboarding_perm_notif_text),
                granted = hasNotif,
                onGrant = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            )
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    text: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        if (granted) {
                            R.string.onboarding_perm_granted
                        } else {
                            R.string.onboarding_perm_needed
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (granted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (granted) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                Button(onClick = onGrant) {
                    Text(stringResource(R.string.action_allow))
                }
            }
        }
    }
}

/** Точки прогресса + Назад/Далее. На последнем экране «Далее» = «Начать». */
@Composable
private fun BottomBar(page: Int, onBack: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (page > 0) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.action_back))
            }
        } else {
            // Баланс ширины: без заглушки точки уезжают вправо на первом экране.
            Spacer(Modifier.width(64.dp))
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (index == page) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == page) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            },
                        ),
                )
            }
        }
        Button(onClick = onNext) {
            Text(
                stringResource(
                    if (page == 2) R.string.onboarding_finish else R.string.onboarding_next,
                ),
            )
        }
    }
}

/** Скругление совпадает с карточками приложения, отдельный стиль не нужен. */
@Suppress("unused")
private val OnboardingCardShape = RoundedCornerShape(12.dp)
