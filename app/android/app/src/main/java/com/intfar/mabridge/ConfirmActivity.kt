package com.intfar.mabridge

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle

/**
 * Open RustDesk on a family member's confirm prompt (design §4). An activity, not a service
 * PendingIntent like the STOP buttons, so the phone has to be unlocked first: on API 31+ the
 * action itself asks for that, and whenever the keyguard is still locked here (in practice
 * API 29-30) this asks to dismiss it and goes on only once it's gone. It has no UI of its own:
 * it hands the request id to the service and finishes. A cancelled or failed unlock leaves
 * the request waiting until its deadline.
 */
class ConfirmActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated during the unlock (a rotation, say): the first instance already asked, so this
        // one mustn't send Open again. It finishes, so no invisible activity is left over the
        // screen; if the first instance's unlock was lost with it, the request times out.
        if (savedInstanceState != null) {
            finish()
            return
        }
        val id = intent.getStringExtra(BridgeService.EXTRA_CONFIRM_ID)
        val km = getSystemService(KeyguardManager::class.java)
        if (km != null && km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = open(id)
                override fun onDismissCancelled() = finish()
                override fun onDismissError() = finish()
            })
        } else {
            open(id)
        }
    }

    private fun open(id: String?) {
        // The service decides: a request that has ended in the meantime runs nothing.
        startService(
            Intent(this, BridgeService::class.java)
                .setAction(BridgeService.ACTION_CONFIRM_OPEN)
                .putExtra(BridgeService.EXTRA_CONFIRM_ID, id)
        )
        finish()
    }
}
