/**
 * The common Java 17 Molang declaration contract for builtin and external authors.
 *
 * <p>
 * Public static functions need no component or constructor. Member declarations implement {@code EntityComponent<E>};
 * inherited generic methods are resolved against the declaring owner, and their exact {@code MolangContextView<E>} or
 * {@code MolangActionView<E>} subject must match its component subject. Optional/defaulted or bounded typed-varargs
 * functions take {@code int argc} immediately after context; defaults form a contiguous scalar suffix and do not change
 * supplied argc.
 *
 * <p>
 * Parameters are boolean, int, float or String. boolean maps to BOOL and int/float to F32. A symbol may name same-owner
 * overloads with distinct normalized parameter signatures; int and float are not distinct signatures. A group shares
 * subject, view, effect and return contract. Float and primitive float function results use missing for non-finite
 * values; boxed scalar null is missing. Finite signed zero and primitive false remain present. String null becomes the
 * empty string, whose content determines presence.
 *
 * <p>
 * Composite results are public non-generic flat records, FloatList, List&lt;String&gt; or List&lt;R&gt;. Record fields
 * are primitive boolean/int/float or String. Only record results may declare
 * {@link com.elfmcys.ysm.api.annotation.MolangNullable}; other annotations do not change this contract. Lists and their
 * elements are non-null. Providers own immutable, finite content and stable effect-free record accessors. Read-only
 * references are shared; language record mutation uses a separate managed value and field/element equality. Optional,
 * union, sound-id, nested records and boxed numeric lists are not expression types. Java int uses finite F32 narrowing;
 * int defaults retain exact Java literal bits.
 *
 * <p>
 * A provider borrows its view only for the synchronous call. Both views refer to the same round context. CONTEXT can
 * read and update ordinary owner state but cannot request actions. ACTION also requires the fixed entry permission and
 * host submission eligibility. No thread-safety or provider sandbox is implied. Models, render resources and network
 * authority remain owned by YSM.
 *
 * <p>
 * The published YSM JAR is also the annotationProcessor dependency. Both entry markers use the same DeclarationSchema
 * and emitter. Namespace controls script names; the actual source file determines mod ownership. Startup verifies safe
 * resources and all class qualifications, rejects every conflict endpoint, then resolves implementations and freezes
 * one registry. Manual mode submits exactly one source-qualified precheck receipt through
 * {@link com.elfmcys.ysm.api.molang.MolangDeclarations}; late submissions are rejected. Rejected classes publish no
 * placeholder symbols.
 */
package com.elfmcys.ysm.api.molang;
