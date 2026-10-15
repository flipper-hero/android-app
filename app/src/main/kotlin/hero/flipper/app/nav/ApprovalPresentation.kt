package hero.flipper.app.nav

enum class RiskLevel { Low, Medium, High, Blocked }

enum class ConfirmControl { None, Allow, Hold }

data class ApprovalPresentation(
    val title: String,
    val badge: String?,
    val control: ConfirmControl,
    val dismissOnBack: Boolean,
    val dismissOnScrim: Boolean,
) {
    companion object {
        fun forRisk(level: RiskLevel, permissionChange: Boolean): ApprovalPresentation {
            val high = level == RiskLevel.High || permissionChange
            return ApprovalPresentation(
                title = if (permissionChange) "Allow permission change?" else "Approve action",
                badge = when {
                    permissionChange || level == RiskLevel.High -> "HIGH RISK"
                    level == RiskLevel.Medium -> "MEDIUM RISK"
                    else -> null
                },
                control = if (high) ConfirmControl.Hold else ConfirmControl.Allow,
                dismissOnBack = false,
                dismissOnScrim = false,
            )
        }
    }
}
