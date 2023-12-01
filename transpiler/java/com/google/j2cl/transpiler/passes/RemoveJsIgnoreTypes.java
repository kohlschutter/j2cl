package com.google.j2cl.transpiler.passes;

import com.google.j2cl.transpiler.ast.CompilationUnit;

public class RemoveJsIgnoreTypes extends NormalizationPass {

  @Override
  public void applyTo(CompilationUnit compilationUnit) {
    compilationUnit.getTypes().removeIf(type -> (type.getDeclaration().isJsIgnoreType()));
  }
}
