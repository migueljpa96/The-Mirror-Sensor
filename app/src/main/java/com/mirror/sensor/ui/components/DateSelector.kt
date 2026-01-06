package com.mirror.sensor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun DateSelector(
    selectedDate: Date,
    onDateSelected: (Date) -> Unit
) {
    val dates = remember {
        val list = mutableListOf<Date>()
        val cal = Calendar.getInstance()
        for (i in 0..13) { // Last 2 weeks
            list.add(cal.time)
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        list.reversed()
    }

    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        if (dates.isNotEmpty()) {
            listState.scrollToItem(dates.lastIndex)
        }
    }

    LazyRow(
        state = listState,
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(dates) { date ->
            DatePill(
                date = date,
                isSelected = isSameDay(date, selectedDate),
                onClick = { onDateSelected(date) }
            )
        }
    }
}

@Composable
fun DatePill(
    date: Date,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val dayName = SimpleDateFormat("EEE", Locale.getDefault()).format(date).uppercase()
    val dayNumber = SimpleDateFormat("d", Locale.getDefault()).format(date)

    val containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val elevation = if (isSelected) 4.dp else 0.dp

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = elevation,
        modifier = Modifier
            .width(56.dp) // Fixed width for uniformity
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 12.dp)
        ) {
            Text(
                text = dayName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                color = contentColor.copy(alpha = 0.7f),
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = dayNumber,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor
            )
        }
    }
}

private fun isSameDay(d1: Date, d2: Date): Boolean {
    val fmt = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    return fmt.format(d1) == fmt.format(d2)
}