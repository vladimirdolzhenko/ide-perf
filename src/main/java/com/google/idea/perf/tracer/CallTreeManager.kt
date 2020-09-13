/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.idea.perf.tracer

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.system.measureNanoTime

// Things to improve:
// * GC the state for dead threads.

/** Builds and manages the call trees for active threads. */
object CallTreeManager {

    // Guarded by thread-local lock (low contention).
    private class ThreadState {
        val lock = ReentrantLock()
        var busy = false // See doPreventingRecursion().
        var callTreeBuilder = CallTreeBuilder()
    }

    private val allThreadState = CopyOnWriteArrayList<ThreadState>()

    private val threadState: ThreadLocal<ThreadState> =
        ThreadLocal.withInitial {
            ThreadState().also { allThreadState.add(it) }
        }

    fun enter(tracepoint: Tracepoint) {
        val state = threadState.get()
        doWithLockAndAdjustOverhead(state) {
            doPreventingRecursion(state) {
                state.callTreeBuilder.push(tracepoint)
            }
        }
    }

    fun leave() {
        val state = threadState.get()
        doWithLockAndAdjustOverhead(state) {
            doPreventingRecursion(state) {
                state.callTreeBuilder.pop()
            }
        }
    }

    fun getCallTreeSnapshotAllThreadsMerged(): CallTree {
        val mergedTree = MutableCallTree(Tracepoint.ROOT)
        for (threadState in allThreadState) {
            threadState.lock.withLock {
                val tree = threadState.callTreeBuilder.getUpToDateTree()
                mergedTree.accumulate(tree)
            }
        }
        return mergedTree
    }

    fun clearCallTrees() {
        for (threadState in allThreadState) {
            threadState.lock.withLock {
                threadState.callTreeBuilder.clear()
            }
        }
    }

    // Subtracts lock contention overhead if needed.
    // This should only be called by the thread that owns the ThreadState.
    private inline fun doWithLockAndAdjustOverhead(state: ThreadState, action: () -> Unit) {
        val lock = state.lock
        val lockOverhead = when {
            lock.tryLock() -> 0L
            else -> measureNanoTime { lock.lock() }
        }
        try {
            if (lockOverhead != 0L) state.callTreeBuilder.subtractOverhead(lockOverhead)
            action()
        }
        finally {
            lock.unlock()
        }
    }

    // Helps prevent StackOverflowError if the user has instrumented a callee of enter() or leave().
    private inline fun doPreventingRecursion(state: ThreadState, action: () -> Unit) {
        if (!state.busy) {
            state.busy = true
            try {
                action()
            }
            finally {
                state.busy = false
            }
        }
    }
}
