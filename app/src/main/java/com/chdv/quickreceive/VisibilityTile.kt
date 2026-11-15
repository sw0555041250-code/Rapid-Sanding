package com.chdv.quickreceive

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick settings tile: visible to everyone / not available. */
class VisibilityTile : TileService() {
    override fun onStartListening() = update()

    override fun onClick() {
        val p = Prefs(this)
        p.visibility = if (p.visibility == "off") "everyone" else "off"
        ReceiverService.refresh(this)
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val on = Prefs(this).visibility != "off"
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "שיתוף מהיר"
        tile.subtitle = if (on) "גלוי לכולם" else "לא זמין"
        tile.updateTile()
    }
}
