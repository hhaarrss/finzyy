package com.smartspend.app.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CategoryIconsTest {

    /** Reads the backend's canonical lists so a category added there without an icon fails here. */
    private fun backendList(name: String): List<String> {
        val file = listOf("../../backend/constants/categories.py", "../backend/constants/categories.py")
            .map(::File).first { it.exists() }
        val body = Regex("""$name: List\[str\] = \[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())!!.groupValues[1]
        return Regex("\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.toList()
    }

    @Test
    fun appListsMatchBackend() {
        assertEquals(backendList("DEBIT_CATEGORIES"), Categories.debit)
        assertEquals(backendList("CREDIT_CATEGORIES"), Categories.credit)
    }

    @Test
    fun everyCategoryHasItsOwnIcon() {
        val specific = Categories.all.filter { it != Categories.OTHER }
        specific.forEach { assertNotEquals("$it fell back to the generic icon", Icons.Rounded.Category, CategoryIcon(it)) }
        assertEquals("icons should be distinct", specific.size, specific.map(::CategoryIcon).toSet().size)
    }

    @Test
    fun legacySpellingsResolve() {
        assertEquals(Categories.FOOD, Categories.canonical("food"))
        assertEquals(Categories.TRANSPORTATION, Categories.canonical("Transport"))
        assertEquals(Categories.UTILITIES, Categories.canonical(" utilities "))
        assertEquals(Categories.OTHER, Categories.canonical("Miscellaneous"))
        assertTrue(Categories.canonical("Something New") == null)
        assertEquals(Icons.Rounded.Category, CategoryIcon("Something New"))
    }
}
