/*
 * Copyright 2020 Google Inc.
 * Copyright 2024 Christian Kohlschütter
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
package com.google.j2cl.transpiler.passes;

import com.google.j2cl.transpiler.ast.AbstractRewriter;
import com.google.j2cl.transpiler.ast.BooleanLiteral;
import com.google.j2cl.transpiler.ast.CompilationUnit;
import com.google.j2cl.transpiler.ast.Expression;
import com.google.j2cl.transpiler.ast.IfStatement;
import com.google.j2cl.transpiler.ast.Node;

/**
 * Rewrites conditional expressions that are always true or false.
 */
public class RewriteIfStatements extends NormalizationPass {
  @Override
  public void applyTo(CompilationUnit compilationUnit) {
    compilationUnit.accept(new AbstractRewriter() {
      @Override
      public Node rewriteIfStatement(IfStatement expression) {
        Expression condition = expression.getConditionExpression();

        if (condition instanceof BooleanLiteral) {
          BooleanLiteral lit = (BooleanLiteral) condition;
          if (lit.getValue()) {
            // true ? trueExpression : falseExpression
            return expression.getThenStatement();
          } else {
            // false ? trueExpression : falseExpression
            return expression.getElseStatement();
          }
        } else {
          return expression;
        }
      }
    });
  }
}
