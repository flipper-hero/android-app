package hero.flipper.app

import hero.flipper.app.nav.ApprovalPresentation
import hero.flipper.app.nav.ConfirmControl
import hero.flipper.app.nav.HeroNav
import hero.flipper.app.nav.HeroSection
import hero.flipper.app.nav.RiskLevel
import hero.flipper.app.theme.ThemeColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavAndThemeTest {
    @Test
    fun destinationOrderMatchesIos() {
        val nav = HeroNav()
        assertEquals(
            listOf(
                HeroSection.Device,
                HeroSection.Remote,
                HeroSection.Files,
                HeroSection.Agent,
                HeroSection.Settings,
            ),
            nav.destinationOrder(),
        )
        assertEquals(HeroSection.Device, nav.section)
    }

    @Test
    fun approvalIgnoresBackAndScrimUntilDeny() {
        val nav = HeroNav()
        nav.showApproval()
        assertFalse(nav.systemBack())
        nav.scrimTap()
        assertTrue(nav.approvalVisible)
        nav.denyApproval()
        assertFalse(nav.approvalVisible)
    }

    @Test
    fun filePreviewLivesOnTheFilesStack() {
        val nav = HeroNav()
        nav.select(HeroSection.Files)
        nav.pushFilePreview()
        assertEquals(hero.flipper.app.nav.FilesRoute.Preview, nav.filesRoute())
        assertTrue(nav.systemBack())
        assertEquals(hero.flipper.app.nav.FilesRoute.List, nav.filesRoute())
    }

    @Test
    fun remoteStaysLtr() {
        assertTrue(HeroNav().remoteLayoutDirectionLtr)
    }

    @Test
    fun highRiskHoldsAndDoesNotDismiss() {
        val sheet = ApprovalPresentation.forRisk(RiskLevel.High, permissionChange = false)
        assertEquals("Approve action", sheet.title)
        assertEquals("HIGH RISK", sheet.badge)
        assertEquals(ConfirmControl.Hold, sheet.control)
        assertFalse(sheet.dismissOnBack)
        assertFalse(sheet.dismissOnScrim)
        val permission = ApprovalPresentation.forRisk(RiskLevel.Low, permissionChange = true)
        assertEquals("Allow permission change?", permission.title)
        assertEquals(ConfirmControl.Hold, permission.control)
        val medium = ApprovalPresentation.forRisk(RiskLevel.Medium, permissionChange = false)
        assertEquals(ConfirmControl.Allow, medium.control)
        assertEquals("MEDIUM RISK", medium.badge)
    }

    @Test
    fun themeMatchesIosOrangeAndStatus() {
        assertEquals(0xFF8200, ThemeColors.accent)
        assertEquals(0x0A0A0B, ThemeColors.background)
        assertEquals(ThemeColors.accent, ThemeColors.userBubble)
        assertEquals(0x4DD973, ThemeColors.ok)
        assertEquals(0xFF524D, ThemeColors.danger)
    }
}
