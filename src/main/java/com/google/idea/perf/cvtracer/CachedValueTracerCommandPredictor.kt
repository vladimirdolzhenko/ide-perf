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

package com.google.idea.perf.cvtracer

import com.google.idea.perf.AgentLoader
import com.google.idea.perf.util.FuzzySearcher
import com.google.idea.perf.util.shouldHideClassFromCompletionResults
import com.intellij.openapi.progress.ProgressManager

class CachedValueTracerCommandPredictor: CommandPredictor {
    private val searcher = FuzzySearcher()

    override fun predict(text: String, offset: Int): List<String> {
        val tokens = text.trimStart().split(' ', '\t')
        val normalizedText = tokens.joinToString(" ")
        val command = parseCachedValueTracerCommand(normalizedText)
        val tokenIndex = getTokenIndex(normalizedText, offset)
        val token = tokens.getOrElse(tokenIndex) { "" }

        return when (tokenIndex) {
            0 -> predictToken(
                listOf("clear", "reset", "filter", "clear-filters", "group-by"), token
            )
            1 -> when (command) {
                is CachedValueTracerCommand.Filter -> {
                    val instrumentation = AgentLoader.instrumentation ?: return emptyList()
                    val classNames = instrumentation.allLoadedClasses
                        .filterNot(::shouldHideClassFromCompletionResults)
                        .map { it.name }
                    predictToken(classNames, token)
                }
                is CachedValueTracerCommand.GroupBy -> predictToken(
                    listOf("class", "stack-trace"), token
                )
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun predictToken(choices: Collection<String>, token: String): List<String> {
        return searcher.search(choices, token, 100) { ProgressManager.checkCanceled() }
            .map { it.source }
    }

    private fun getTokenIndex(input: String, index: Int): Int {
        return input.subSequence(0, index).count { it.isWhitespace() || it == '#' }
    }
}
