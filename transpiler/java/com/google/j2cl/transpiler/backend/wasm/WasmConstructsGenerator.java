/*
 * Copyright 2020 Google Inc.
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
package com.google.j2cl.transpiler.backend.wasm;

import static com.google.common.base.Predicates.not;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.String.format;
import static java.util.Arrays.stream;

import com.google.common.collect.ImmutableList;
import com.google.j2cl.common.StringUtils;
import com.google.j2cl.transpiler.ast.AbstractVisitor;
import com.google.j2cl.transpiler.ast.ArrayLiteral;
import com.google.j2cl.transpiler.ast.ArrayTypeDescriptor;
import com.google.j2cl.transpiler.ast.DeclaredTypeDescriptor;
import com.google.j2cl.transpiler.ast.Expression;
import com.google.j2cl.transpiler.ast.Field;
import com.google.j2cl.transpiler.ast.FieldDescriptor;
import com.google.j2cl.transpiler.ast.Library;
import com.google.j2cl.transpiler.ast.Method;
import com.google.j2cl.transpiler.ast.MethodDescriptor;
import com.google.j2cl.transpiler.ast.NumberLiteral;
import com.google.j2cl.transpiler.ast.PrimitiveTypeDescriptor;
import com.google.j2cl.transpiler.ast.Type;
import com.google.j2cl.transpiler.ast.TypeDeclaration;
import com.google.j2cl.transpiler.ast.TypeDescriptor;
import com.google.j2cl.transpiler.ast.TypeDescriptors;
import com.google.j2cl.transpiler.ast.Variable;
import com.google.j2cl.transpiler.backend.common.SourceBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Generates all the syntactic .wat constructs for wasm. */
public class WasmConstructsGenerator {

  private final SourceBuilder builder;
  private WasmGenerationEnvironment environment;

  public WasmConstructsGenerator(WasmGenerationEnvironment environment, SourceBuilder builder) {
    this.environment = environment;
    this.builder = builder;
  }

  void emitDataSegments(Library library) {
    library.accept(
        new AbstractVisitor() {
          @Override
          public void exitArrayLiteral(ArrayLiteral arrayLiteral) {
            if (canBeMovedToDataSegment(arrayLiteral)
                && environment.registerDataSegmentLiteral(
                    arrayLiteral, getCurrentType().getDeclaration().getQualifiedBinaryName())) {
              var dataElementName = environment.getDataElementNameForLiteral(arrayLiteral);
              builder.newLine();
              builder.append(
                  format("(data %s \"%s\")", dataElementName, toDataString(arrayLiteral)));
            }
          }
        });
  }

  private boolean canBeMovedToDataSegment(ArrayLiteral arrayLiteral) {
    return TypeDescriptors.isNonVoidPrimitiveType(
            arrayLiteral.getTypeDescriptor().getComponentTypeDescriptor())
        && arrayLiteral.getValueExpressions().stream().allMatch(NumberLiteral.class::isInstance);
  }

  /**
   * Encodes an array literal of primitive values as a sequence of bytes, in UTF8 encoding for
   * readability.
   */
  private String toDataString(ArrayLiteral arrayLiteral) {
    PrimitiveTypeDescriptor componentTypeDescriptor =
        (PrimitiveTypeDescriptor) arrayLiteral.getTypeDescriptor().getComponentTypeDescriptor();
    int sizeInBits = componentTypeDescriptor.getWidth();
    List<Expression> valueExpressions = arrayLiteral.getValueExpressions();

    // Preallocate the stringbuilder to hold the encoded data since its size its already known.
    StringBuilder sb = new StringBuilder(valueExpressions.size() * (sizeInBits / 8));
    for (Expression expression : valueExpressions) {
      NumberLiteral literal = (NumberLiteral) expression;
      long value;
      PrimitiveTypeDescriptor typeDescriptor = literal.getTypeDescriptor();
      if (TypeDescriptors.isPrimitiveFloat(typeDescriptor)) {
        value = Float.floatToRawIntBits(literal.getValue().floatValue());
      } else if (TypeDescriptors.isPrimitiveDouble(typeDescriptor)) {
        value = Double.doubleToRawLongBits(literal.getValue().doubleValue());
      } else {
        value = literal.getValue().longValue();
      }

      for (int s = sizeInBits; s > 0; s -= 8, value >>>= 8) {
        sb.append(StringUtils.escapeAsUtf8((int) (value & 0xFF)));
      }
    }
    return sb.toString();
  }

  /** Emits all wasm type definitions into a single rec group. */
  void emitLibraryRecGroup(Library library, List<ArrayTypeDescriptor> usedNativeArrayTypes) {
    builder.newLine();
    builder.append("(rec");
    builder.indent();

    emitDynamicDispatchMethodTypes();
    emitItableSupportTypes();
    emitNativeArrayTypes(usedNativeArrayTypes);
    emitForEachType(library, this::renderMonolithicTypeStructs, "type definition");

    builder.unindent();
    builder.newLine();
    builder.append(")");
  }

  private void emitItableSupportTypes() {
    builder.newLine();
    // The itable is a struct that contains only interface vtables. Interfaces are assigned a slot
    // on this struct based on the classes that implement them.
    builder.append("(type $itable (sub (struct ");
    for (int slot = 0; slot < environment.getNumberOfInterfaceSlots(); slot++) {
      builder.newLine();
      builder.append(
          format("(field %s (ref null struct))", environment.getInterfaceSlotFieldName(slot)));
    }
    builder.newLine();
    builder.append(")))");
  }

  void emitGlobals(Library library) {
    emitStaticFieldGlobals(library);
  }

  /** Emit the type for all function signatures that will be needed to reference vtable methods. */
  void emitDynamicDispatchMethodTypes() {
    environment.collectMethodsThatNeedTypeDeclarations().forEach(this::emitFunctionType);
  }

  void emitFunctionType(String typeName, MethodDescriptor m) {
    builder.newLine();
    builder.append(format("(type %s (func", typeName));
    emitFunctionParameterTypes(m);
    emitFunctionResultType(m);
    builder.append("))");
  }

  /**
   * Emit the necessary imports of binaryen intrinsics.
   *
   * <p>In order to communicate information to binaryen, binaryen provides intrinsic methods that
   * need to be imported.
   */
  void emitImportsForBinaryenIntrinsics() {

    // Emit the intrinsic for calls with no side effects. The intrinsic method exported name is
    // "call.without.effects" and can be used to convey to binaryen that a certain function call
    // does not have side effects.
    // Since the mechanism itself is a call, it needs to be correctly typed. As a result for each
    // different function type that appears in the AST as part of no-side-effect call, an import
    // with the right function type definition needs to be created.
    environment
        .collectMethodsNeedingIntrinsicDeclarations()
        .forEach(this::emitBinaryenIntrinsicImport);
  }

  void emitBinaryenIntrinsicImport(String typeName, MethodDescriptor m) {
    builder.newLine();
    builder.append(
        format(
            "(import \"binaryen-intrinsics\" \"call.without.effects\" " + "(func %s ", typeName));
    emitFunctionParameterTypes(m);
    builder.append(" (param funcref)");
    emitFunctionResultType(m);
    builder.append("))");
  }

  private void emitFunctionParameterTypes(MethodDescriptor methodDescriptor) {
    if (!methodDescriptor.isStatic()) {
      // Add the implicit parameter
      builder.append(
          format(
              " (param (ref %s))",
              environment.getWasmTypeName(TypeDescriptors.get().javaLangObject)));
    }
    methodDescriptor
        .getDispatchParameterTypeDescriptors()
        .forEach(t -> builder.append(format(" (param %s)", environment.getWasmType(t))));
  }

  private void emitFunctionResultType(MethodDescriptor methodDescriptor) {
    TypeDescriptor returnTypeDescriptor = methodDescriptor.getDispatchReturnTypeDescriptor();
    if (!TypeDescriptors.isPrimitiveVoid(returnTypeDescriptor)) {
      builder.append(format(" (result %s)", environment.getWasmType(returnTypeDescriptor)));
    }
  }

  void emitExceptionTag() {
    // Declare a tag that will be used for Java exceptions. The tag has a single parameter that is
    // the Throwable object being thrown by the throw instruction.
    // The throw instruction will refer to this tag and will expect a single element in the stack
    // with the type $java.lang.Throwable.
    // TODO(b/277970998): Decide how to handle this hard coded import w.r.t. import generation.
    builder.newLine();
    builder.append(
        "(import \"imports\" \"j2wasm.ExceptionUtils.tag\" (tag $exception.event (param"
            + " externref)))");
    // Add an export that uses the tag to workarund binaryen assuming the tag is never instantiated.
    builder.append(
        "(func $keep_tag_alive_hack (export \"_tag_hack_\") (param $param externref)  "
            + "(throw $exception.event (local.get $param)))");
  }

  private void renderMonolithicTypeStructs(Type type) {
    renderTypeStructs(type, /* isModular= */ false);
  }

  void renderModularTypeStructs(Type type) {
    renderTypeStructs(type, /* isModular= */ true);
  }

  private void renderTypeStructs(Type type, boolean isModular) {
    if (type.isNative() || type.getDeclaration().getWasmInfo() != null) {
      return;
    }
    if (type.isInterface()) {
      // Interfaces at runtime are treated as java.lang.Object.
      renderInterfaceVtableStruct(type);
    } else {
      renderTypeStruct(type);
      renderClassVtableStruct(type);
      if (!isModular) {
        renderClassItableStruct(type);
      }
    }
  }

  private void renderClassItableStruct(Type type) {
    TypeDeclaration typeDeclaration = type.getDeclaration();
    if (!typeDeclaration.implementsInterfaces()) {
      return;
    }
    emitItableType(typeDeclaration, getItableSlots(typeDeclaration));
  }

  /** Renders the struct for the vtable of a class. */
  private void renderClassVtableStruct(Type type) {
    WasmTypeLayout wasmTypeLayout = environment.getWasmTypeLayout(type.getDeclaration());
    renderVtableStruct(type, wasmTypeLayout.getAllPolymorphicMethods());
  }

  /**
   * Renders the struct for the vtable of an interface.
   *
   * <p>There is a vtable for each interface, and it consists of fields only for the methods
   * declared in that interface (not including methods declared in their supers). Calls to interface
   * methods will always point to an interface that declared them.
   */
  private void renderInterfaceVtableStruct(Type type) {
    // TODO(b/186472671): centralize all concepts related to layout in WasmTypeLayout, including
    // interface vtables and slot assignments.
    renderVtableStruct(
        type,
        type.getDeclaration().getDeclaredMethodDescriptors().stream()
            .filter(MethodDescriptor::isPolymorphic)
            .collect(Collectors.toList()));
  }

  private void renderVtableStruct(Type type, Collection<MethodDescriptor> methods) {
    emitWasmStruct(type, environment::getWasmVtableTypeName, () -> renderVtableEntries(methods));
  }

  private void renderVtableEntries(Collection<MethodDescriptor> methodDescriptors) {
    methodDescriptors.forEach(
        m -> {
          builder.newLine();
          builder.append(
              format(
                  "(field $%s (ref %s))", m.getMangledName(), environment.getFunctionTypeName(m)));
        });
  }

  private void emitStaticFieldGlobals(Library library) {
    library.streamTypes().forEach(this::emitStaticFieldGlobals);
  }

  private void emitStaticFieldGlobals(Type type) {
    emitBeginCodeComment(type, "static fields");
    for (Field field : type.getStaticFields()) {
      builder.newLine();
      builder.append("(global " + environment.getFieldName(field));

      if (field.isCompileTimeConstant()) {
        builder.append(
            format(" %s", environment.getWasmType(field.getDescriptor().getTypeDescriptor())));
        builder.indent();
        builder.newLine();
        ExpressionTranspiler.render(field.getInitializer(), builder, environment);
        builder.unindent();
      } else {
        builder.append(
            format(
                " (mut %s)", environment.getWasmType(field.getDescriptor().getTypeDescriptor())));
        builder.indent();
        builder.newLine();
        ExpressionTranspiler.render(
            field.getDescriptor().getTypeDescriptor().getDefaultValue(), builder, environment);
        builder.unindent();
      }

      builder.newLine();
      builder.append(")");
    }
    emitEndCodeComment(type, "static fields");
  }

  void renderTypeMethods(Type type) {
    type.getMethods().stream()
        .filter(method -> !method.isAbstract() || method.isNative())
        .filter(m -> m.getDescriptor().getWasmInfo() == null)
        .forEach(this::renderMethod);
  }

  void renderMethod(Method method) {
    MethodDescriptor methodDescriptor = method.getDescriptor();
    // TODO(b/264676817): Consider refactoring to have MethodDescriptor.isNative return true for
    // native constructors, or exposing isNativeConstructor from MethodDescriptor.
    boolean isNativeConstructor =
        methodDescriptor.getEnclosingTypeDescriptor().isNative()
            && methodDescriptor.isConstructor();
    JsMethodImport jsMethodImport = environment.getJsMethodImport(methodDescriptor);
    if (jsMethodImport == null && isNativeConstructor) {
      // TODO(b/279187295): These are implicit constructors of native types that don't really exist,
      // remove this check once they are removed from the AST.
      return;
    }
    builder.newLine();
    builder.newLine();
    builder.append(";;; " + method.getReadableDescription());
    builder.newLine();
    builder.append("(func " + environment.getMethodImplementationName(methodDescriptor));

    // Generate an import if the method is native. We don't use the normal qualified js name,
    // because it doesn't differentiate between js property getters and setters.
    if (jsMethodImport != null) {
      builder.append(
          format(
              " (import \"%s\" \"%s\") ",
              JsImportsGenerator.MODULE, jsMethodImport.getImportKey()));
    }

    if (method.isWasmEntryPoint()) {
      builder.append(" (export \"" + method.getWasmExportName() + "\")");
    }

    DeclaredTypeDescriptor enclosingTypeDescriptor = methodDescriptor.getEnclosingTypeDescriptor();

    // Emit parameters
    builder.indent();
    // Add the implicit "this" parameter to instance methods and constructors.
    // Note that constructors and private methods can declare the parameter type to be the
    // enclosing type because they are not overridden but normal instance methods have to
    // declare the parameter more generically as java.lang.Object, since all the overrides need
    // to have matching signatures.
    if (methodDescriptor.isClassDynamicDispatch()
        && !methodDescriptor.isNative()
        && !methodDescriptor.isJsOverlay()) {
      builder.newLine();
      builder.append(format("(type %s)", environment.getFunctionTypeName(methodDescriptor)));
      builder.newLine();
      builder.append(
          format(
              "(param $this.untyped (ref %s))",
              environment.getWasmTypeName(TypeDescriptors.get().javaLangObject)));
    } else if (!method.isStatic() && !isNativeConstructor) {
      // Private methods and constructors receive the instance with the actual type.
      // Native constructors do not receive the instance.
      builder.newLine();
      builder.append(format("(param $this %s)", environment.getWasmType(enclosingTypeDescriptor)));
    }

    for (Variable parameter : method.getParameters()) {
      builder.newLine();
      builder.append(
          "(param "
              + environment.getDeclarationName(parameter)
              + " "
              + environment.getWasmType(parameter.getTypeDescriptor())
              + ")");
    }

    TypeDescriptor returnTypeDescriptor = methodDescriptor.getDispatchReturnTypeDescriptor();

    // Emit return type.
    if (!TypeDescriptors.isPrimitiveVoid(returnTypeDescriptor)) {
      builder.newLine();
      builder.append("(result " + environment.getWasmType(returnTypeDescriptor) + ")");
    }

    if (jsMethodImport != null) {
      // Imports don't define locals nor body.
      builder.unindent();
      builder.newLine();
      builder.append(")");
      return;
    }

    // Emit a source mapping at the entry of a method so that when stepping into a method
    // the debugger shows the right source line.
    StatementTranspiler.renderSourceMappingComment(method.getSourcePosition(), builder);

    // Emit locals.
    for (Variable variable : collectLocals(method)) {
      builder.newLine();
      builder.append(
          "(local "
              + environment.getDeclarationName(variable)
              + " "
              + environment.getWasmType(variable.getTypeDescriptor())
              + ")");
    }
    // Introduce the actual $this variable for polymorphic methods and cast the parameter to
    // the right type.
    if (methodDescriptor.isClassDynamicDispatch() && !methodDescriptor.isJsOverlay()) {
      builder.newLine();
      builder.append(format("(local $this %s)", environment.getWasmType(enclosingTypeDescriptor)));
      builder.newLine();
      // Use non-nullable `ref.cast` since the receiver of an instance method should
      // not be null, and it is ok to fail for devirtualized methods if it is.
      builder.append(
          format(
              "(local.set $this (ref.cast (ref %s) (local.get $this.untyped)))",
              environment.getWasmTypeName(enclosingTypeDescriptor)));
    }

    StatementTranspiler.render(method.getBody(), builder, environment);
    builder.unindent();
    builder.newLine();
    builder.append(")");

    // Declare a function that will be target of dynamic dispatch.
    if (methodDescriptor.isPolymorphic()) {
      builder.newLine();
      builder.append(
          format(
              "(elem declare func %s)",
              environment.getMethodImplementationName(method.getDescriptor())));
    }
  }

  private static List<Variable> collectLocals(Method method) {
    List<Variable> locals = new ArrayList<>();
    method
        .getBody()
        .accept(
            new AbstractVisitor() {
              @Override
              public void exitVariable(Variable variable) {
                locals.add(variable);
              }
            });
    return locals;
  }

  private void renderTypeStruct(Type type) {
    emitWasmStruct(type, environment::getWasmTypeName, () -> renderTypeFields(type));
  }

  private void renderTypeFields(Type type) {
    // The first field is always the vtable for class dynamic dispatch.
    builder.newLine();
    builder.append(
        format(
            "(field $vtable (ref %s))",
            environment.getWasmVtableTypeName(type.getTypeDescriptor())));
    // The second field is always the itable for interface method dispatch.
    builder.newLine();
    builder.append(
        format(
            "(field $itable (ref %s))", environment.getWasmItableTypeName(type.getDeclaration())));

    WasmTypeLayout wasmType = environment.getWasmTypeLayout(type.getDeclaration());
    for (FieldDescriptor fieldDescriptor : wasmType.getAllInstanceFields()) {
      builder.newLine();
      String fieldType = environment.getWasmFieldType(fieldDescriptor.getTypeDescriptor());

      // TODO(b/296475021): Cleanup the handling of the arrays elements field.
      if (!environment.isWasmArrayElementsField(fieldDescriptor)) {
        fieldType = format("(mut %s)", fieldType);
      }
      builder.append(format("(field %s %s)", environment.getFieldName(fieldDescriptor), fieldType));
    }
  }

  /**
   * Emit a function that will be used to initialize the runtime at module instantiation time;
   * together with the required type definitions.
   */
  void emitDispatchTablesInitialization(Library library) {
    builder.newLine();
    // TODO(b/183994530): Initialize dynamic dispatch tables lazily.
    builder.append(";;; Initialize dynamic dispatch tables.");

    // Emit an empty itable that will be used for types that don't implement any interface.
    builder.newLine();
    builder.append("(global $itable.empty (ref $itable)");
    builder.indent();
    builder.newLine();
    builder.append("(struct.new_default $itable)");
    builder.unindent();
    builder.newLine();
    builder.append(")");

    // Populate all vtables.
    library
        .streamTypes()
        .filter(not(Type::isInterface))
        .filter(not(Type::isNative))
        .map(Type::getDeclaration)
        .filter(not(TypeDeclaration::isAbstract))
        .filter(type -> type.getWasmInfo() == null)
        .forEach(this::emitDispatchTablesInitialization);
    builder.newLine();
  }

  private void emitDispatchTablesInitialization(TypeDeclaration typeDeclaration) {
    emitClassVtableInitialization(typeDeclaration);
    emitItableInitialization(typeDeclaration);
  }

  /** Emits the code to initialize the class vtable structure for {@code typeDeclaration}. */
  private void emitClassVtableInitialization(TypeDeclaration typeDeclaration) {
    WasmTypeLayout wasmTypeLayout = environment.getWasmTypeLayout(typeDeclaration);

    emitBeginCodeComment(typeDeclaration, "vtable.init");
    builder.newLine();
    //  Create the class vtable for this type (which is either a class or an enum) and store it
    // in a global variable to be able to use it to initialize instance of this class.
    builder.append(
        format(
            "(global %s (ref %s)",
            environment.getWasmVtableGlobalName(typeDeclaration),
            environment.getWasmVtableTypeName(typeDeclaration)));
    builder.indent();
    emitVtableInitialization(typeDeclaration, wasmTypeLayout.getAllPolymorphicMethods());
    builder.unindent();
    builder.newLine();
    builder.append(")");
    emitEndCodeComment(typeDeclaration, "vtable.init");
  }

  /** Emits the code to initialize the Itable array for {@code typeDeclaration}. */
  private void emitItableInitialization(TypeDeclaration typeDeclaration) {
    if (!typeDeclaration.implementsInterfaces()) {
      return;
    }
    emitBeginCodeComment(typeDeclaration, "itable.init");

    // Create the struct of interface vtables of the required size and store it in a global variable
    // to be able to use it when objects of this class are instantiated.
    builder.newLine();
    // Emit globals for each interface vtable
    WasmTypeLayout wasmTypeLayout = environment.getWasmTypeLayout(typeDeclaration);
    TypeDeclaration[] itableSlots = getItableSlots(typeDeclaration);
    stream(itableSlots)
        .filter(Objects::nonNull)
        .forEach(
            i -> {
              builder.newLine();
              builder.append(
                  format(
                      "(global %s (ref %s)",
                      environment.getWasmInterfaceVtableGlobalName(i, typeDeclaration),
                      environment.getWasmVtableTypeName(i)));
              builder.indent();
              initializeInterfaceVtable(wasmTypeLayout, i);
              builder.unindent();
              builder.newLine();
              builder.append(")");
            });
    builder.newLine();
    builder.append(
        format(
            "(global %s (ref %s)",
            environment.getWasmItableGlobalName(typeDeclaration),
            environment.getWasmItableTypeName(typeDeclaration)));
    builder.indent();
    builder.newLine();
    builder.append(format("(struct.new %s", environment.getWasmItableTypeName(typeDeclaration)));
    builder.indent();
    stream(itableSlots)
        .forEach(
            i -> {
              builder.newLine();
              if (i == null) {
                builder.append(" (ref.null struct)");
                return;
              }
              builder.append(
                  format(
                      " (global.get %s)",
                      environment.getWasmInterfaceVtableGlobalName(i, typeDeclaration)));
            });
    builder.unindent();
    builder.newLine();
    builder.append(")");
    builder.unindent();
    builder.newLine();
    builder.append(")");
    emitEndCodeComment(typeDeclaration, "itable.init");
  }

  private TypeDeclaration[] getItableSlots(TypeDeclaration typeDeclaration) {
    ImmutableList<TypeDeclaration> superInterfaces =
        typeDeclaration.getAllSuperTypesIncludingSelf().stream()
            .filter(TypeDeclaration::isInterface)
            .collect(toImmutableList());

    // Compute the itable for this type.
    int numSlots = environment.getNumberOfInterfaceSlots();
    TypeDeclaration[] itableSlots = new TypeDeclaration[numSlots];
    superInterfaces.forEach(
        superInterface ->
            itableSlots[environment.getInterfaceSlot(superInterface)] = superInterface);
    return itableSlots;
  }

  /** Emits a specialized itable type for this type to allow for better optimizations. */
  private void emitItableType(TypeDeclaration typeDeclaration, TypeDeclaration[] itableSlots) {
    // Create the specialized struct for the itable for this type. A specialized itable type will
    // be a subtype of the specialized itable type for its superclass. Note that the struct fields
    // get incrementally specialized in this struct in the subclasses as the interfaces are
    // implemented by them.
    builder.newLine();
    builder.append(
        format(
            "(type %s (sub %s (struct",
            environment.getWasmItableTypeName(typeDeclaration),
            environment.getWasmItableTypeName(typeDeclaration.getSuperTypeDeclaration())));
    for (int slot = 0; slot < environment.getNumberOfInterfaceSlots(); slot++) {
      builder.newLine();
      builder.append(format("(field %s ", environment.getInterfaceSlotFieldName(slot)));
      if (itableSlots[slot] == null) {
        // This type does not use the struct, so it is kept at the generic struct type.
        builder.append("(ref null struct))");
      } else {
        builder.append(format("(ref %s))", environment.getWasmVtableTypeName(itableSlots[slot])));
      }
    }
    builder.newLine();
    builder.append(")))");
  }

  private void initializeInterfaceVtable(
      WasmTypeLayout wasmTypeLayout, TypeDeclaration interfaceDeclaration) {
    ImmutableList<MethodDescriptor> interfaceMethodImplementations =
        interfaceDeclaration.getDeclaredMethodDescriptors().stream()
            .filter(MethodDescriptor::isPolymorphic)
            .map(wasmTypeLayout::getImplementationMethod)
            .collect(toImmutableList());
    emitVtableInitialization(interfaceDeclaration, interfaceMethodImplementations);
  }

  /**
   * Creates and initializes the vtable for {@code implementedType} with the methods in {@code
   * methodDescriptors}.
   *
   * <p>This is used to initialize both class vtables and interface vtables. Each concrete class
   * will have a class vtable to implement the dynamic class method dispatch and one vtable for each
   * interface it implements to implement interface dispatch.
   */
  private void emitVtableInitialization(
      TypeDeclaration implementedType, Collection<MethodDescriptor> methodDescriptors) {
    builder.newLine();
    // Create an instance of the vtable for the type initializing it with the methods that are
    // passed in methodDescriptors.
    builder.append(format("(struct.new %s", environment.getWasmVtableTypeName(implementedType)));

    builder.indent();
    methodDescriptors.forEach(
        m -> {
          builder.newLine();
          builder.append(format("(ref.func %s)", environment.getMethodImplementationName(m)));
        });
    builder.unindent();
    builder.newLine();
    builder.append(")");
  }

  /** Emits a Wasm struct using nominal inheritance. */
  private void emitWasmStruct(
      Type type, Function<DeclaredTypeDescriptor, String> structNamer, Runnable fieldsRenderer) {
    boolean hasSuperType = type.getSuperTypeDescriptor() != null;
    builder.newLine();
    builder.append(String.format("(type %s (sub ", structNamer.apply(type.getTypeDescriptor())));
    if (hasSuperType) {
      builder.append(format("%s ", structNamer.apply(type.getSuperTypeDescriptor())));
    }
    builder.append("(struct");
    builder.indent();
    fieldsRenderer.run();

    builder.newLine();
    builder.append(")");
    builder.append(")");
    builder.unindent();
    builder.newLine();
    builder.append(")");
  }

  void emitNativeArrayTypes(List<ArrayTypeDescriptor> arrayTypes) {
    emitBeginCodeComment("Native Array types");
    arrayTypes.forEach(this::emitNativeArrayType);
    emitEndCodeComment("Native Array types");
  }

  private void emitNativeArrayType(ArrayTypeDescriptor arrayTypeDescriptor) {
    String wasmArrayTypeName = environment.getWasmTypeName(arrayTypeDescriptor);
    builder.newLine();
    builder.append(
        format(
            "(type %s (array (mut %s)))",
            wasmArrayTypeName,
            environment.getWasmFieldType(arrayTypeDescriptor.getComponentTypeDescriptor())));
  }

  void emitEmptyArraySingletons(List<ArrayTypeDescriptor> arrayTypes) {
    emitBeginCodeComment("Empty array singletons");
    arrayTypes.forEach(this::emitEmptyArraySingleton);
    emitEndCodeComment("Empty array singletons");
  }

  private void emitEmptyArraySingleton(ArrayTypeDescriptor arrayTypeDescriptor) {
    String wasmArrayTypeName = environment.getWasmTypeName(arrayTypeDescriptor);
    // Emit a global empty array singleton to avoid allocating empty arrays. */
    builder.newLine();
    builder.append(
        format(
            "(global %s (ref %s)",
            environment.getWasmEmptyArrayGlobalName(arrayTypeDescriptor), wasmArrayTypeName));
    builder.indent();
    builder.newLine();
    builder.append(format("(array.new_default %s (i32.const 0))", wasmArrayTypeName));
    builder.unindent();
    builder.newLine();
    builder.append(")");
    builder.newLine();
  }

  /**
   * Iterate through all the types in the library, supertypes first, calling the emitter for each of
   * them.
   */
  void emitForEachType(Library library, Consumer<Type> emitter, String comment) {
    library
        .streamTypes()
        // Emit the types supertypes first.
        .sorted(Comparator.comparing(t -> t.getDeclaration().getClassHierarchyDepth()))
        .forEach(
            type -> {
              emitBeginCodeComment(type, comment);
              emitter.accept(type);
              emitEndCodeComment(type, comment);
            });
  }

  private void emitBeginCodeComment(Type type, String section) {
    emitBeginCodeComment(type.getDeclaration(), section);
  }

  private void emitBeginCodeComment(TypeDeclaration typeDeclaration, String section) {
    emitBeginCodeComment(format("%s [%s]", typeDeclaration.getQualifiedSourceName(), section));
  }

  private void emitBeginCodeComment(String commentId) {
    builder.newLine();
    builder.append(";;; Code for " + commentId);
  }

  private void emitEndCodeComment(Type type, String section) {
    emitEndCodeComment(type.getDeclaration(), section);
  }

  private void emitEndCodeComment(TypeDeclaration typeDeclaration, String section) {
    emitEndCodeComment(format("%s [%s]", typeDeclaration.getQualifiedSourceName(), section));
  }

  private void emitEndCodeComment(String commentId) {
    builder.newLine();
    builder.append(";;; End of code for " + commentId);
  }
}
