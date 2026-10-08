package com.smartspend.app.ui.preview

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.smartspend.app.ui.addtransaction.DateField
import com.smartspend.app.ui.auth.OtpInput
import com.smartspend.app.ui.categories.BreakdownPeriod
import com.smartspend.app.ui.categories.CategoryTotal
import com.smartspend.app.ui.categories.DonutBlock
import com.smartspend.app.ui.components.CenteredContent
import com.smartspend.app.ui.components.ChartBar
import com.smartspend.app.ui.components.PrimaryButton
import com.smartspend.app.ui.components.ScreenGutter
import com.smartspend.app.ui.components.SecondaryButton
import com.smartspend.app.ui.components.SpendBarChart
import com.smartspend.app.ui.components.TransactionRow
import com.smartspend.app.ui.components.TxView
import com.smartspend.app.ui.onboarding.OnboardingCarousel
import com.smartspend.app.ui.theme.FinzyyTheme

/*
 * Layout previews: the same component at several screen sizes and font scales, with awkward
 * data (long names, big amounts). Open this file in Android Studio's Split/Design view.
 *
 * Why these sizes:
 *  - 320 x 640   the smallest phones still in use
 *  - 411 x 891   a typical modern phone
 *  - 480 x 1000  a large phone
 *  - 840 x 1180  a tablet / unfolded foldable (the app caps content at MaxContentWidth)
 *  - 1.5x and 2.0x font: the failure mode no screen size reveals. Anything with a fixed height
 *    or width clips here. 2.0x is the largest "Font size" setting on most phones.
 *
 * These are only compiled in; nothing in the app calls them, so R8 drops them from release.
 */
@Preview(name = "Small 320", group = "size", device = "spec:width=320dp,height=640dp")
@Preview(name = "Normal 411", group = "size", device = "spec:width=411dp,height=891dp")
@Preview(name = "Large 480", group = "size", device = "spec:width=480dp,height=1000dp")
@Preview(name = "Tablet 840", group = "size", device = "spec:width=840dp,height=1180dp")
@Preview(name = "Font 1.5x", group = "font", fontScale = 1.5f, device = "spec:width=411dp,height=891dp")
@Preview(
    name = "Dark, font 2.0x", group = "font", fontScale = 2f,
    uiMode = Configuration.UI_MODE_NIGHT_YES, device = "spec:width=360dp,height=740dp"
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
annotation class DevicePreviews

@Composable
private fun Frame(content: @Composable () -> Unit) {
    FinzyyTheme(darkTheme = isSystemInDarkTheme()) {
        Surface(color = MaterialTheme.colorScheme.background) { content() }
    }
}

@Composable
private fun Padded(content: @Composable () -> Unit) = Frame {
    CenteredContent {
        Column(
            Modifier.padding(ScreenGutter),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) { content() }
    }
}

/** Page 1 has the most going on: scene, headline, body and two pinned buttons. */
@DevicePreviews
@Composable
private fun OnboardingFirstPage() = Frame {
    CenteredContent { OnboardingCarousel(onSignUp = {}, onLogIn = {}, startPage = 0) }
}

/** Last page: the second ("Log in") button is showing, so the footer is at its tallest. */
@DevicePreviews
@Composable
private fun OnboardingLastPage() = Frame {
    CenteredContent { OnboardingCarousel(onSignUp = {}, onLogIn = {}, startPage = 2) }
}

@DevicePreviews
@Composable
private fun Controls() = Padded {
    PrimaryButton(label = "Save and add another", onClick = {})
    SecondaryButton(label = "Continue without auto-sync", onClick = {})
    DateField(label = "Wednesday, 30 September 2026", onClick = {})
    OtpInput(code = "48", onCodeChange = {}, isError = false, focusRequester = remember { FocusRequester() })
}

/** Five-figure amounts make the widest axis labels ("₹1.2L"), which used to be clipped. */
@DevicePreviews
@Composable
private fun SpendChart() = Padded {
    SpendBarChart(
        bars = listOf(
            ChartBar("Apr", 82_000.0), ChartBar("May", 125_000.0), ChartBar("Jun", 96_500.0),
            ChartBar("Jul", 143_200.0), ChartBar("Aug", 110_000.0), ChartBar("Sep", 64_300.0)
        ),
        selectedIndex = 3,
        onSelect = {},
        accessibilitySummary = "Monthly spending"
    )
}

/** Long category names in the three-entry legend; it wraps instead of running off the edge. */
@DevicePreviews
@Composable
private fun CategoryBreakdown() = Padded {
    DonutBlock(
        categories = listOf(
            CategoryTotal("Finance & Insurance", 24_000.0, 12),
            CategoryTotal("Telecom & Recharge", 18_000.0, 6),
            CategoryTotal("Food & Dining", 9_000.0, 20),
            CategoryTotal("Groceries", 3_000.0, 9)
        ),
        period = BreakdownPeriod.ThisMonth
    )
}

/** Long merchant names must end in an ellipsis and never push the amount off-screen. */
@DevicePreviews
@Composable
private fun TransactionRows() = Padded {
    TransactionRow(
        tx = TxView(
            id = 1, amount = 124_500.0, isCredit = false, category = "Finance & Insurance",
            merchant = "RABADIYA HARSH GIRIS PRIVATE LIMITED", date = "2026-09-28",
            fromSms = true, needsReview = false, bank = "HDFC", last4 = "1021"
        ),
        onClick = {}
    )
    TransactionRow(
        tx = TxView(
            id = 2, amount = 420.0, isCredit = false, category = "Needs Review",
            merchant = "PAYTM*QR*9876543210@ICICI", date = "2026-09-28",
            fromSms = true, needsReview = true, bank = "Axis", last4 = "07"
        ),
        onClick = {}
    )
}
