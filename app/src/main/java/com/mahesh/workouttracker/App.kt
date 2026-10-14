package com.mahesh.workouttracker

import android.app.Application

/** v3.9: applies the saved theme palette before any activity renders.
 *  [Palettes.current] defaults to Material in code as well, so even a
 *  first frame can never flash the wrong palette. The Settings Theme
 *  segment re-applies + relaunches the task on change. */
class WtApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Palettes.apply(this)
    }
}
