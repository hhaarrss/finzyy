package com.smartspend.app.ui.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/** The question opens on Sunday, Tuesday and Friday; the other days show the teaser. */
class MoneyMinuteScheduleTest {

    // 12 Oct 2026 is a Monday.
    private val monday = LocalDate.of(2026, 10, 12)
    private fun day(offset: Long) = monday.plusDays(offset)

    @Test
    fun theDatesUsedHereAreTheWeekdaysTheyClaimToBe() {
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, day(6).dayOfWeek)
    }

    @Test
    fun questionDaysAreSundayTuesdayAndFridayOnly() {
        val open = (0L..6L).filter { isMoneyMinuteDay(day(it)) }.map { day(it).dayOfWeek }
        assertEquals(listOf(DayOfWeek.TUESDAY, DayOfWeek.FRIDAY, DayOfWeek.SUNDAY), open)
        assertFalse(isMoneyMinuteDay(monday))
        assertTrue(isMoneyMinuteDay(day(1)))
    }

    @Test
    fun nextQuestionDayIsAlwaysLaterThanToday() {
        assertEquals(day(1), nextMoneyMinuteDay(monday))      // Mon -> Tue
        assertEquals(day(4), nextMoneyMinuteDay(day(1)))      // Tue -> Fri, not Tue itself
        assertEquals(day(4), nextMoneyMinuteDay(day(2)))      // Wed -> Fri
        assertEquals(day(4), nextMoneyMinuteDay(day(3)))      // Thu -> Fri
        assertEquals(day(6), nextMoneyMinuteDay(day(4)))      // Fri -> Sun
        assertEquals(day(6), nextMoneyMinuteDay(day(5)))      // Sat -> Sun
        assertEquals(day(8), nextMoneyMinuteDay(day(6)))      // Sun -> next Tue
    }

    @Test
    fun theTeaserSaysTomorrowOrNamesTheDay() {
        assertEquals("tomorrow", opensLabel(monday, Locale.ENGLISH))        // Mon: opens Tue
        assertEquals("Friday", opensLabel(day(2), Locale.ENGLISH))          // Wed
        assertEquals("tomorrow", opensLabel(day(3), Locale.ENGLISH))        // Thu: opens Fri
        assertEquals("tomorrow", opensLabel(day(5), Locale.ENGLISH))        // Sat: opens Sun
    }
}
