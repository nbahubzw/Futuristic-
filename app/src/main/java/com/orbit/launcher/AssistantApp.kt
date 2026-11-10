package com.orbit.launcher

import android.graphics.drawable.Drawable

// Orbit Assistant, file 1 of 4: the data it works with.

/** One installed app, as the assistant sees it. */
data class AssistantApp(
    val label: String,
    val packageName: String,
    val activityName: String,
    val group: String,
    val icon: Drawable?
) {
    internal val norm: String = OrbitMatcher.normalise(label)
    internal val words: List<String> = OrbitMatcher.splitWords(label)
    internal val initials: String = words.joinToString("") { it.take(1) }
}

/** What the assistant decided to do with what you typed or said. */
sealed class OrbitCommand {
    data class OpenApp(val app: AssistantApp) : OrbitCommand()
    data class Dial(val number: String) : OrbitCommand()
    data class CallContact(val name: String) : OrbitCommand()
    data class ShowGroup(val group: String) : OrbitCommand()
    data class NotFound(val query: String) : OrbitCommand()
    object OpenDialler : OrbitCommand()
    object Empty : OrbitCommand()
}
