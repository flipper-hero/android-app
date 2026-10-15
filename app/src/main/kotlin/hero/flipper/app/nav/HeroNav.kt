package hero.flipper.app.nav

/** Section order matches the iOS tab bar: Device, Remote, Files, Agent, Settings. */
enum class HeroSection(val title: String) {
    Device("Device"),
    Remote("Remote"),
    Files("Files"),
    Agent("Agent"),
    Settings("Settings"),
}

enum class FilesRoute { List, Preview }

enum class ConfirmKind { Restart, Firmware, Yolo }

/**
 * Navigation policy the Compose shell starts from.
 * Each section keeps its own back stack. Approval is not a back-stack entry:
 * system Back and scrim taps leave it up, and only Deny dismisses it.
 */
class HeroNav(
    section: HeroSection = HeroSection.Device,
) {
    var section: HeroSection = section
        private set

    private val stacks: MutableMap<HeroSection, ArrayDeque<String>> =
        HeroSection.entries.associateWith { ArrayDeque<String>() }.toMutableMap()

    var approvalVisible: Boolean = false
        private set

    var confirm: ConfirmKind? = null
        private set

    var renameVisible: Boolean = false
        private set

    /** Remote and the D-pad stay left-to-right even when the rest of the UI is RTL. */
    val remoteLayoutDirectionLtr: Boolean = true

    fun select(next: HeroSection) {
        section = next
    }

    fun pushFilePreview() {
        require(section == HeroSection.Files)
        stacks.getValue(HeroSection.Files).addLast(FilesRoute.Preview.name)
    }

    fun filesRoute(): FilesRoute =
        if (stacks.getValue(HeroSection.Files).isEmpty()) FilesRoute.List else FilesRoute.Preview

    fun showApproval() {
        approvalVisible = true
    }

    /** System Back and the scrim. Approval stays. Other surfaces pop or dismiss. */
    fun systemBack(): Boolean {
        if (approvalVisible) return false
        if (confirm != null) {
            confirm = null
            return true
        }
        if (renameVisible) {
            renameVisible = false
            return true
        }
        val stack = stacks.getValue(section)
        if (stack.isNotEmpty()) {
            stack.removeLast()
            return true
        }
        return false
    }

    fun scrimTap() {
        if (approvalVisible) return
        confirm = null
        renameVisible = false
    }

    /** Only Deny dismisses the approval surface. */
    fun denyApproval() {
        approvalVisible = false
    }

    fun approveHeld() {
        approvalVisible = false
    }

    fun showConfirm(kind: ConfirmKind) {
        confirm = kind
    }

    fun acceptConfirm() {
        confirm = null
    }

    fun showRename() {
        renameVisible = true
    }

    fun destinationOrder(): List<HeroSection> = HeroSection.entries
}
