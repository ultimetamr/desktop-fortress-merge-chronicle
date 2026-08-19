package com.example.desktopfortress.manager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job

abstract class BaseManager {
    protected var managerScope = newScope()
        private set

    abstract fun initialize()
    abstract fun destroy()

    protected fun recreateScopeIfNeeded() {
        if (managerScope.coroutineContext[Job]?.isActive != true) managerScope = newScope()
    }

    protected fun cancelScope() = managerScope.cancel()

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
