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

import com.google.j2cl.transpiler.ast.NewInstance
import com.google.j2cl.transpiler.ast.Type
import com.google.j2cl.transpiler.ast.TypeDeclaration
import com.google.j2cl.transpiler.ast.TypeDeclaration.Kind
import com.google.j2cl.transpiler.ast.TypeDescriptors.isJavaLangEnum
import com.google.j2cl.transpiler.ast.TypeDescriptors.isJavaLangObject
import com.google.j2cl.transpiler.backend.kotlin.ast.kotlinMembers
import java.util.stream.Collectors

fun Renderer.renderType(type: Type) {
  // Don't render KtNative types. We should never see them except readables.
  if (type.declaration.isKtNative) {
    render("// native class ")
    renderIdentifier(type.declaration.ktSimpleName)
    return
  }

  if (type.isClass && !type.declaration.isFinal) {
    if (type.declaration.isAbstract) render("abstract ") else render("open ")
  }
  if (
    type.enclosingTypeDeclaration != null &&
      type.kind == Kind.CLASS &&
      !type.isStatic &&
      !type.declaration.isLocal
  ) {
    render("inner ")
  }
  render(
    when (type.kind) {
      Kind.CLASS -> "class "
      Kind.ENUM -> "enum class "
      Kind.INTERFACE -> (if (type.declaration.isFunctionalInterface) "fun " else "") + "interface "
    }
  )
  renderTypeDeclaration(type.declaration)

  renderSuperTypes(type)
  renderWhereClause(type.declaration.typeParameterDescriptors)
  renderTypeBody(type)
}

fun Renderer.renderTypeDeclaration(declaration: TypeDeclaration) {
  renderIdentifier(declaration.ktSimpleName)
  declaration.directlyDeclaredTypeParameterDescriptors
    .takeIf { it.isNotEmpty() }
    ?.let { parameters -> renderTypeParameters(parameters) }
}

private fun Renderer.renderSuperTypes(type: Type) {
  val superTypes =
    type.superTypesStream
      .filter { !isJavaLangObject(it) }
      .filter { !isJavaLangEnum(it) }
      .collect(Collectors.toList())
  if (superTypes.isNotEmpty()) {
    val hasConstructors = type.constructors.isNotEmpty()
    render(": ")
    renderCommaSeparated(superTypes) { superType ->
      renderTypeDescriptor(superType.toNonNullable(), asSuperType = true)
      if (superType.isClass && !hasConstructors) render("()")
    }
  }
}

internal fun Renderer.renderTypeBody(type: Type) {
  copy(currentType = type, renderThisReferenceWithLabel = false).run {
    render(" ")
    renderInCurlyBrackets {
      if (type.isEnum) {
        renderEnumValues(type)
      }

      val kotlinMembers = type.kotlinMembers
      if (kotlinMembers.isNotEmpty()) {
        renderNewLine()
        renderSeparatedWithEmptyLine(kotlinMembers) { render(it) }
      }
    }
  }
}

private fun Renderer.renderEnumValues(type: Type) {
  renderNewLine()
  renderSeparatedWith(type.enumFields, ",\n") { field ->
    renderIdentifier(field.descriptor.name!!)
    val newInstance = field.initializer as NewInstance

    if (newInstance.arguments.isNotEmpty()) {
      renderInvocationArguments(newInstance)
    }

    newInstance.anonymousInnerClass?.let { renderTypeBody(it) }
  }
  render(";\n")
}
