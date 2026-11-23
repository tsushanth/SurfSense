package com.kreativekoala.surfsense.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kreativekoala.surfsense.insights.CategoryNames

/** Display name of a category in the app language. [raw] stays the English storage key everywhere else. */
@Composable
fun categoryName(raw: String): String = CategoryNames.resFor(raw)?.let { stringResource(it) } ?: raw
