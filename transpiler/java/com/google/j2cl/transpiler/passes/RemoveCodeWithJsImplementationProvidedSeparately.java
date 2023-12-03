/*
 * Copyright 2023 Google Inc.
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
package com.google.j2cl.transpiler.passes;

import com.google.j2cl.transpiler.ast.AbstractRewriter;
import com.google.j2cl.transpiler.ast.Expression;
import com.google.j2cl.transpiler.ast.Method;
import com.google.j2cl.transpiler.ast.Method.Builder;
import com.google.j2cl.transpiler.ast.MethodDescriptor;
import com.google.j2cl.transpiler.ast.Node;
import com.google.j2cl.transpiler.ast.ReturnStatement;
import com.google.j2cl.transpiler.ast.Type;
import com.google.j2cl.transpiler.ast.TypeDescriptor;
import com.google.j2cl.transpiler.ast.TypeDescriptors;

/**
 * Removes method body contents declared with @{code @JsImplementationProvidedSeparately}, adding a
 * default return statement if necessary.
 */
public final class RemoveCodeWithJsImplementationProvidedSeparately extends NormalizationPass {
  @Override
  public void applyTo(Type type) {
    type.accept(new AbstractRewriter() {

      @Override
      public Node rewriteMethod(Method method) {
        MethodDescriptor methodDescriptor = method.getDescriptor();
        if (!methodDescriptor.isJsImplementationProvidedSeparately()) {
          return method;
        }

        Builder builder = Method.newBuilder().setMethodDescriptor(methodDescriptor).setParameters(
            method.getParameters()).setSourcePosition(method.getSourcePosition());

        TypeDescriptor returnTypeDescriptor = methodDescriptor.getReturnTypeDescriptor();
        if (!TypeDescriptors.isPrimitiveVoid(returnTypeDescriptor) && !method.getBody()
            .getStatements().isEmpty()) {
          Expression expr = returnTypeDescriptor.getDefaultValue();
          if (expr != null) {
            builder.setStatements(ReturnStatement.newBuilder().setExpression(expr)
                .setSourcePosition(method.getSourcePosition()).build());
          }
        }

        return builder.build();
      }
    });
  }
}
