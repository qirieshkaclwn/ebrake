package xyz.imd4n.ebrake.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.imd4n.ebrake.ui.gallery.VideoLibrary

/**
 * Кадр-обложка ролика для карточек очереди и истории: в списке одного файла,
 * взятого в трёх уровнях качества, ориентироваться можно глазами.
 *
 * Переиспользует общий [VideoLibrary.cover]: единый LRU-кэш на 12 МБ,
 * аппаратное декодирование через ContentResolver на Android 10+, масштабированное
 * чтение кадра без OOM на 4K-роликах и кэширование неудачных попыток.
 */
@Composable
fun VideoThumbnail(
    uri: Uri?,
    modifier: Modifier = Modifier,
    width: Dp = 96.dp,
    aspect: Float = 16f / 9f,
) {
    val context = LocalContext.current
    val widthPx = with(LocalDensity.current) { width.roundToPx() }
    val heightPx = with(LocalDensity.current) { (width / aspect).roundToPx() }
    val targetPx = maxOf(widthPx, heightPx).coerceAtLeast(1)

    val bitmap by produceState<Bitmap?>(initialValue = null, uri, targetPx) {
        value = if (uri != null) {
            VideoLibrary.cover(context, uri, targetPx)
        } else {
            null
        }
    }

    Box(
        modifier = modifier
            .width(width)
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val frame = bitmap
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
