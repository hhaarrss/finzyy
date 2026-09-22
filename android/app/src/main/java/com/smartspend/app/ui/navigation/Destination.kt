package com.smartspend.app.ui.navigation

/**
 * Every navigable destination in the app.
 *
 * Route strings are persisted in the saved back stack across process death, so they are
 * part of the app's stored state — renaming one invalidates a restored back stack.
 */
enum class Destination(val route: String) {
    Home("home"),
    Search("search?review={review}"),
    AddTransaction("add_transaction"),
    Budget("budget"),
    Categories("categories"),
    CategoryDetail("category/{name}"),
    Trends("trends"),
    Insights("insights"),
    Account("account"),
    SmsConsent("sms_consent");

    companion object {
        fun search(reviewOnly: Boolean) = "search?review=$reviewOnly"
        fun category(name: String) = "category/" + android.net.Uri.encode(name)
    }
}
