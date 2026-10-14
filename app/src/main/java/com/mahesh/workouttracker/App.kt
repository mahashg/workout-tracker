package com.mahesh.workouttracker

import android.app.Application

/** v3.9: applies the saved theme palette before any activity renders.
 *  [Palettes.current] defaults to Material in code as well, so even a
 *  first frame can never flash the wrong palette. The Settings Theme
 *  segment re-applies on change and Settings recreates in place
 *  (v3.9.1 — no task relaunch). */
class WtApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Palettes.apply(this)
    }
}
