package com.intfar.mabridge.protocol

/** A registry command (PROTOCOL.md §4). [text] is its exact wire form. */
sealed interface Command {
    val text: String

    data class Launch(val app: String) : Command {
        override val text get() = "launch $app"
    }
    object RingFind : Command { override val text = "ring find" }
    object StatusReport : Command { override val text = "status report" }
    object BootstrapRustdesk : Command { override val text = "bootstrap rustdesk" }
    object UpdateRustdesk : Command { override val text = "update rustdesk" }
}

/** Every command allowed on [channel] (§4.1 owner, §4.2 family). */
fun commandsFor(channel: Channel, registry: Registry): List<Command> {
    val shared = registry.apps.keys.map { Command.Launch(it) } + Command.RingFind
    return when (channel) {
        Channel.FAMILY -> shared
        Channel.OWNER -> shared + listOf(
            Command.StatusReport, Command.BootstrapRustdesk, Command.UpdateRustdesk,
        )
    }
}

/**
 * The command whose wire text is exactly [body], if [channel] allows it.
 * No trimming, case folding or partial matching (§2.2, §5.2).
 */
fun parseCommand(body: String, channel: Channel, registry: Registry): Command? =
    commandsFor(channel, registry).firstOrNull { it.text == body }
