package com.smartspend.app.ui.money

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smartspend.app.DailyQuestionData
import com.smartspend.app.RetrofitClient
import com.smartspend.app.ui.components.Block
import com.smartspend.app.ui.components.Eyebrow
import com.smartspend.app.ui.theme.FinzyyTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

private const val PREFS = "smart_spend_prefs"
private const val KEY_ANSWERED_DATE = "money_minute_answered_date"
private const val KEY_ANSWERED_CORRECT = "money_minute_answered_correct"

/**
 * The question is asked three days a week, not every day: a thing that is always there stops
 * being opened. On the other days the card is a teaser for the next one.
 */
internal val MoneyMinuteDays = setOf(DayOfWeek.SUNDAY, DayOfWeek.TUESDAY, DayOfWeek.FRIDAY)

internal fun isMoneyMinuteDay(day: LocalDate): Boolean = day.dayOfWeek in MoneyMinuteDays

/** The next day the question opens, strictly after [from]. */
internal fun nextMoneyMinuteDay(from: LocalDate): LocalDate =
    (1L..7L).map { from.plusDays(it) }.first { isMoneyMinuteDay(it) }

/** "tomorrow", or the weekday name. */
internal fun opensLabel(from: LocalDate, locale: Locale = Locale.getDefault()): String {
    val next = nextMoneyMinuteDay(from)
    return if (next == from.plusDays(1)) "tomorrow"
    else next.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, locale)
}

/** One line per day, so the teaser is not the same sentence all week. */
private val TeaserLines = listOf(
    "A number about your week is waiting.",
    "Think you know where your money went?",
    "Your next guess is on its way.",
    "One question. Ten seconds. How close will you get?",
)

/**
 * "Money minute": one question a day about the user's own spending, answered in place on Home.
 *
 * People are bad at guessing what they spend, and being wrong is the moment they want to look
 * closer — so the reveal leads into the category. Today's answer is remembered on the device,
 * so the card shows the result instead of the question for the rest of the day.
 */
@Composable
fun MoneyMinuteCard(
    refreshKey: Int,
    onCategory: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val day = LocalDate.now()
    if (!isMoneyMinuteDay(day)) {
        MoneyMinuteTeaser(day, modifier)
        return
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var question by remember { mutableStateOf<DailyQuestionData?>(null) }
    var guess by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(refreshKey) {
        val loaded = runCatching { RetrofitClient.apiService.getDailyQuestion().body() }.getOrNull()
        question = loaded?.takeIf { it.available }
        // Already played today: go straight to the reveal rather than asking again.
        if (loaded != null && prefs.getString(KEY_ANSWERED_DATE, null) == today()) {
            guess = if (prefs.getBoolean(KEY_ANSWERED_CORRECT, false)) loaded.answer else -1
        }
    }

    val current = question ?: return
    val answer = current.answer ?: return

    Block(modifier = modifier, padding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Money minute", modifier = Modifier.weight(1f))
            if (guess != null) {
                Text(
                    if (guess == answer) "Spot on" else "Not quite",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (guess == answer) FinzyyTheme.colors.positive else FinzyyTheme.colors.inkMuted
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            current.prompt.orEmpty(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(14.dp))

        if (guess == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                current.options.forEach { option ->
                    GuessButton(
                        label = "₹%,d".format(option),
                        onClick = {
                            guess = option
                            prefs.edit()
                                .putString(KEY_ANSWERED_DATE, today())
                                .putBoolean(KEY_ANSWERED_CORRECT, option == answer)
                                .apply()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
            Reveal(current, onCategory)
        }
    }
}

/**
 * Shown on the days in between: a hidden amount, when the next question opens, and the week
 * with its three question days marked.
 */
@Composable
private fun MoneyMinuteTeaser(today: LocalDate, modifier: Modifier = Modifier) {
    val opens = opensLabel(today)
    val pulse by rememberInfiniteTransition(label = "teaser").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pulse"
    )
    Block(modifier = modifier, padding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("Money minute", modifier = Modifier.weight(1f))
            Text(
                "Opens $opens",
                style = MaterialTheme.typography.labelLarge,
                color = FinzyyTheme.colors.accent
            )
        }
        Spacer(Modifier.height(12.dp))
        // The amount, kept hidden: three dots that breathe where the digits will be.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clearAndSetSemantics { contentDescription = "A hidden amount" }
        ) {
            Text(
                "₹",
                style = MaterialTheme.typography.displaySmall,
                color = FinzyyTheme.colors.inkMuted,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(10.dp))
            repeat(3) { i ->
                Box(
                    Modifier
                        .size(14.dp)
                        .alpha(if (i == 1) 1.35f - pulse else pulse)
                        .clip(CircleShape)
                        .background(FinzyyTheme.colors.accent)
                )
                Spacer(Modifier.width(8.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            TeaserLines[today.dayOfYear % TeaserLines.size],
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Come back $opens to guess it.",
            style = MaterialTheme.typography.bodyMedium,
            color = FinzyyTheme.colors.inkMuted
        )
        Spacer(Modifier.height(14.dp))
        WeekStrip(today)
    }
}

/** Monday to Sunday: question days filled gold, today ringed. */
@Composable
private fun WeekStrip(today: LocalDate) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        DayOfWeek.values().forEach { dow ->
            val quiz = dow in MoneyMinuteDays
            val isToday = dow == today.dayOfWeek
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (quiz) FinzyyTheme.colors.accent else androidx.compose.ui.graphics.Color.Transparent)
                    .border(
                        BorderStroke(
                            if (isToday) 2.dp else 1.dp,
                            if (isToday) MaterialTheme.colorScheme.onSurface else FinzyyTheme.colors.hairline
                        ),
                        CircleShape
                    )
                    .clearAndSetSemantics {
                        contentDescription = dow.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()) +
                            (if (quiz) ", question day" else "") + (if (isToday) ", today" else "")
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    dow.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (quiz) MaterialTheme.colorScheme.surface else FinzyyTheme.colors.inkMuted,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun GuessButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(46.dp)
            .border(BorderStroke(1.dp, FinzyyTheme.colors.hairline), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun Reveal(question: DailyQuestionData, onCategory: (String) -> Unit) {
    val amount = question.actual_amount ?: 0.0
    val category = question.category.orEmpty()
    AnimatedVisibility(visible = true, enter = fadeIn() + expandVertically()) {
        Column {
            Text(
                "₹%,.0f".format(amount),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(question.transaction_count)
                    append(if (question.transaction_count == 1) " payment" else " payments")
                    append(" this week. ")
                    append(question.comparison.orEmpty())
                },
                style = MaterialTheme.typography.bodyMedium,
                color = FinzyyTheme.colors.inkMuted
            )
            if (category.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "See $category",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onCategory(category) }
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
    }
}

private fun today(): String = LocalDate.now().toString()
