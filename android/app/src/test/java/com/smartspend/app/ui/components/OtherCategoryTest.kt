package com.smartspend.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What gets saved for the text typed under "Other", and where remembered names sit in a picker. */
class OtherCategoryTest {

    private val builtIn = listOf("Food & Dining", "Groceries", "Rent", "Transfer", "Other")

    @Test
    fun nothingTypedKeepsItUnderOther() {
        assertNull(customCategoryFor("", builtIn))
        assertNull(customCategoryFor("   ", builtIn))
        assertNull(customCategoryFor("a", builtIn))
    }

    @Test
    fun aTypedNameIsTidiedAndKept() {
        assertEquals("Pets", customCategoryFor("  Pets ", builtIn))
        assertEquals("Gym & Fitness", customCategoryFor("Gym   &  Fitness", builtIn))
        assertEquals("घर का खर्च", customCategoryFor("घर का खर्च", builtIn))
    }

    @Test
    fun aBuiltInNameIsNotTreatedAsNew() {
        assertEquals("Food & Dining", customCategoryFor("food & dining", builtIn))
        assertEquals("Other", customCategoryFor("OTHER", builtIn))
        assertNull(customCategoryFor("needs review", builtIn))
    }

    @Test
    fun longNamesAreCutToTheLimit() {
        assertEquals(MAX_CUSTOM_CATEGORY_LENGTH, customCategoryFor("x".repeat(80), builtIn)!!.length)
    }

    @Test
    fun rememberedNamesGoBeforeTransferAndOther() {
        assertEquals(
            listOf("Food & Dining", "Groceries", "Rent", "Pets", "Gym", "Transfer", "Other"),
            builtIn.withCustomCategories(listOf("Pets", "Gym"))
        )
    }

    @Test
    fun rememberedNamesNeverDuplicateAChip() {
        assertEquals(builtIn, builtIn.withCustomCategories(emptyList()))
        assertEquals(builtIn, builtIn.withCustomCategories(listOf("groceries", "OTHER")))
        assertEquals(
            listOf("Food & Dining", "Groceries", "Rent", "Pets", "Transfer", "Other"),
            builtIn.withCustomCategories(listOf("Pets", "pets", "PETS"))
        )
    }
}
