package com.google.j2cl.transpiler.backend.closure;

import java.util.ServiceLoader;
import java.util.Set;

/**
 * Metadata regarding a class that can be either a regular entrypoint (annotated with
 * {@code @JsEntryPoint}) or a {@link ServiceLoader} implementation (annotated via
 * {@code @JsServiceProvider}.
 * 
 * @param isEntrypoint Whether the class should included in {@code generated-entrypoints.js}.
 * @param services If non-empty, register the specified {@link ServiceLoader} implementations in an
 *          efficient way with the runtime.
 * @author Christian Kohlschütter
 */
final record EntryPointInfo(boolean isEntrypoint, Set<String> services) {

}
