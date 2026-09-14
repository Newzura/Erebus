package com.newzura.erebus

import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService

class PrivilegedRootService : RootService() {

    companion object {
        private const val TAG = "ErebusRoot"
    }

    override fun onBind(intent: Intent): IBinder {
        Log.i(TAG, "M4: PrivilegedRootService lié via libsu IPC")
        return PrivilegedServiceImpl()
    }

    override fun onUnbind(intent: Intent): Boolean {
        Log.i(TAG, "M4: PrivilegedRootService délié")
        return super.onUnbind(intent)
    }
}
