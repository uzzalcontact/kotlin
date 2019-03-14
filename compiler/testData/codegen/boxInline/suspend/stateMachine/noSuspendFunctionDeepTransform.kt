// IGNORE_BACKEND: JVM_IR
// FILE: inlined.kt
// COMMON_COROUTINES_TEST
// WITH_RUNTIME
// WITH_COROUTINES
// NO_CHECK_LAMBDA_INLINING
// CHECK_STATE_MACHINE

import helpers.*
import COROUTINES_PACKAGE.*

interface Factory {
    fun create(): suspend () -> Unit
}

inline fun inlineMe(crossinline c: suspend () -> Unit): Factory {
    val l2 = {
        val l1 = {
            object : Factory {
                override fun create() = suspend { c(); c() }
            }
        }
        l1()
    }
    return l2()
}


// FILE: inlineSite.kt
// COMMON_COROUTINES_TEST

import COROUTINES_PACKAGE.*
import helpers.*

fun builder(c: suspend () -> Unit) {
    c.startCoroutine(CheckStateMachineContinuation)
}

fun box(): String {
    builder {
        val lambda = suspend {
            StateMachineChecker.suspendHere()
            StateMachineChecker.suspendHere()
        }
        inlineMe(lambda).create()()
    }
    StateMachineChecker.check(4)
    return "OK"
}