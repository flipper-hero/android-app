package hero.flipper.app

import androidx.compose.runtime.Composable
import hero.flipper.agent.AgentEdition
import hero.flipper.agent.FlipperControlling
import hero.flipper.agent.StoreEdition
import hero.flipper.kit.FlipperRpcClient

fun edition(): AgentEdition = StoreEdition

@Suppress("UNUSED_PARAMETER")
fun wrapDevice(inner: FlipperControlling, rpc: FlipperRpcClient): FlipperControlling = inner

@Suppress("UNUSED_PARAMETER")
fun auditEngagementChange(model: HeroModel, enabled: Boolean, note: String) = Unit

@Composable
fun EditionSettings(model: HeroModel) = Unit
