/*
 * Copyright 2021 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.transpiler.backend.kotlin

import com.google.j2cl.transpiler.backend.kotlin.ast.isForbiddenKeyword
import com.google.j2cl.transpiler.backend.kotlin.ast.isHardKeyword

internal fun Renderer.renderIdentifier(identifier: String) {
  // Dollar sign ($) is not a valid identifier character since Kotlin 1.7, as well as many other
  // characters. For now, it is replaced with triple underscores (___) to minimise a risk of
  // conflict.
  // TODO(b/236360941): Implement escaping which would work across all platforms.
  var kotlinIdentifier = identifier.replace("$", "___")
  if (isForbiddenKeyword(kotlinIdentifier)) {
    kotlinIdentifier = kotlinIdentifier + "___forbidden"
  }
  if (isHardKeyword(kotlinIdentifier) || !kotlinIdentifier.isValidIdentifier) {
    renderInBackticks { render(kotlinIdentifier) }
  } else {
    render(kotlinIdentifier)
  }
}

internal fun Renderer.renderPackageName(packageName: String) {
  renderQualifiedIdentifier(packageName)
}

internal fun Renderer.renderQualifiedName(qualifiedName: String) {
  renderQualifiedName(qualifiedName, forceSimple = false)
}

internal fun Renderer.renderExtensionFunctionName(qualifiedName: String) {
  renderQualifiedName(qualifiedName, forceSimple = true)
}

private fun Renderer.renderQualifiedName(qualifiedName: String, forceSimple: Boolean) {
  val simpleName = qualifiedName.qualifiedNameToSimpleName()

  // Import only the first occurrence of simple name
  // TODO(b/226922954): Implement import aliases.
  environment.importedSimpleNameToQualifiedNameMap.putIfAbsent(simpleName, qualifiedName)

  val canRenderSimpleName =
    forceSimple ||
      (environment.importedSimpleNameToQualifiedNameMap[simpleName] == qualifiedName &&
        !localNames.contains(simpleName) &&
        !environment.containsIdentifier(simpleName))
  if (canRenderSimpleName) {
    renderIdentifier(simpleName)
  } else {
    renderQualifiedIdentifier(qualifiedName)
  }
}

private fun Renderer.renderQualifiedIdentifier(identifier: String) {
  renderDotSeparated(identifier.split('.')) { renderIdentifier(it) }
}

private val String.isValidIdentifier
  get() = first().isValidIdentifierFirstChar && all { it.isValidIdentifierChar }

private val Char.isValidIdentifierChar
  get() = isLetterOrDigit() || this == '_'

private val Char.isValidIdentifierFirstChar
  get() = isValidIdentifierChar && !isDigit()
