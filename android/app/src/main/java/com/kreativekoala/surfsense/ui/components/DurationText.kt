package com.kreativekoala.surfsense.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import com.kreativekoala.surfsense.ui.theme.tabularFigures
import com.kreativekoala.surfsense.viewmodel.MainViewModel

/** Same shape as [MainViewModel.formatSeconds], except 1..59 seconds reads "<1m", never "0m". */
fun formatDuration(seconds: Int, lessThanMinute: String = "<1m"): String =
    if (seconds in 1..59) lessThanMinute else MainViewModel.formatSeconds(seconds)

@Composable
fun DurationText(
    seconds: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    lessThanMinute: String = "<1m",
) {
    Text(
        text = formatDuration(seconds, lessThanMinute),
        modifier = modifier,
        style = style.tabularFigures(),
        color = color,
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
    )
}
