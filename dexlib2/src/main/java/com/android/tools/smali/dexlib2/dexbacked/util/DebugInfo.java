/*
 * Copyright 2012, Google LLC
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of Google LLC nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.tools.smali.dexlib2.dexbacked.util;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.DebugItemType;
import com.android.tools.smali.dexlib2.dexbacked.*;
import com.android.tools.smali.dexlib2.iface.MethodParameter;
import com.android.tools.smali.dexlib2.immutable.debug.ImmutableEndLocal;
import com.android.tools.smali.dexlib2.immutable.debug.ImmutableEpilogueBegin;
import com.android.tools.smali.dexlib2.immutable.debug.ImmutableLineNumber;
import com.android.tools.smali.dexlib2.immutable.debug.ImmutablePrologueEnd;
import com.android.tools.smali.dexlib2.immutable.debug.ImmutableRestartLocal;
import com.android.tools.smali.dexlib2.immutable.debug.ImmutableSetSourceFile;
import com.android.tools.smali.dexlib2.immutable.debug.ImmutableStartLocal;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.android.tools.smali.dexlib2.iface.debug.DebugItem;
import com.android.tools.smali.dexlib2.iface.debug.EndLocal;
import com.android.tools.smali.dexlib2.iface.debug.LocalInfo;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Iterator;

public abstract class DebugInfo implements Iterable<DebugItem> {
    /**
     * Gets an iterator that yields the parameter names from the debug_info_item
     *
     * @param reader Optional. If provided, the reader must be positioned at the debug_info_item.parameters_size
     *               field, and will
     * @return An iterator that yields the parameter names as strings
     */
    @Nonnull public abstract Iterator<String> getParameterNames(@Nullable DexReader<? extends DexBuffer> reader);

    /**
     * Calculate and return the private size of debuginfo.
     *
     * @return size in bytes
     */
    public abstract int getSize();

    public static DebugInfo newOrEmpty(@Nonnull DexBackedDexFile dexFile, int debugInfoOffset,
                                       @Nonnull DexBackedMethodImplementation methodImpl) {
        if (debugInfoOffset == 0) {
            return EmptyDebugInfo.INSTANCE;
        }
        return new DebugInfoImpl(dexFile, debugInfoOffset, methodImpl);
    }

    private static class EmptyDebugInfo extends DebugInfo {
        public static final EmptyDebugInfo INSTANCE = new EmptyDebugInfo();
        private EmptyDebugInfo() {}

        @Nonnull @Override public Iterator<DebugItem> iterator() {
            return ImmutableSet.<DebugItem>of().iterator();
        }

        @Nonnull @Override public Iterator<String> getParameterNames(@Nullable DexReader<? extends DexBuffer> reader) {
            return ImmutableSet.<String>of().iterator();
        }

        @Override
        public int getSize() {
            return 0;
        }
    }

    private static class DebugInfoImpl extends DebugInfo {
        @Nonnull public final DexBackedDexFile dexFile;
        private final int debugInfoOffset;
        @Nonnull private final DexBackedMethodImplementation methodImpl;

        public DebugInfoImpl(@Nonnull DexBackedDexFile dexFile,
                         int debugInfoOffset,
                         @Nonnull DexBackedMethodImplementation methodImpl) {
            this.dexFile = dexFile;
            this.debugInfoOffset = debugInfoOffset;
            this.methodImpl = methodImpl;
        }

        private static final LocalInfo EMPTY_LOCAL_INFO = new LocalInfo() {
            @Nullable @Override public String getName() { return null; }
            @Nullable @Override public String getType() { return null; }
            @Nullable @Override public String getSignature() { return null; }
        };

        @Nonnull
        @Override
        public Iterator<DebugItem> iterator() {
            DexReader<? extends DexBuffer> reader = dexFile.getDataBuffer().readerAt(debugInfoOffset);
            final int lineNumberStart = reader.readBigUleb128();
            int registerCount = methodImpl.getRegisterCount();
            // Debug information can have events for addresses past the instructions.
            // They have no relevance for the method in question and are excluded from the iterator.
            final int lastInstructionAddress =
                methodImpl.getInstructionsSize()
                    - Iterators.getLast(methodImpl.getInstructions().iterator()).getCodeUnits();

            //TODO: does dalvik allow references to invalid registers?
            final LocalInfo[] locals = new LocalInfo[registerCount];
            Arrays.fill(locals, EMPTY_LOCAL_INFO);

            DexBackedMethod method = methodImpl.method;

            // Create a MethodParameter iterator that uses our DexReader instance to read the parameter names.
            // After we have finished iterating over the parameters, reader will "point to" the beginning of the
            // debug instructions
            final Iterator<? extends MethodParameter> parameterIterator =
                    new ParameterIterator(method.getParameterTypes(),
                            method.getParameterAnnotations(),
                            getParameterNames(reader));

            // first, we grab all the parameters and temporarily store them at the beginning of locals,
            // disregarding any wide types
            int parameterIndex = 0;
            if (!AccessFlags.STATIC.isSet(methodImpl.method.getAccessFlags())) {
                // add the local info for the "this" parameter
                locals[parameterIndex++] = new LocalInfo() {
                    @Override public String getName() { return "this"; }
                    @Override public String getType() { return methodImpl.method.getDefiningClass(); }
                    @Override public String getSignature() { return null; }
                };
            }
            while (parameterIterator.hasNext()) {
                locals[parameterIndex++] = parameterIterator.next();
            }

            if (parameterIndex < registerCount) {
                // now, we push the parameter locals back to their appropriate register, starting from the end
                int localIndex = registerCount-1;
                while(--parameterIndex > -1) {
                    LocalInfo currentLocal = locals[parameterIndex];
                    String type = currentLocal.getType();
                    if (type != null && (type.equals("J") || type.equals("D"))) {
                        localIndex--;
                        if (localIndex == parameterIndex) {
                            // there's no more room to push, the remaining registers are already in the correct place
                            break;
                        }
                    }
                    locals[localIndex] = currentLocal;
                    locals[parameterIndex] = EMPTY_LOCAL_INFO;
                    localIndex--;
                }
            }

            return new VariableSizeLookaheadIterator<DebugItem>(dexFile.getDataBuffer(), reader.getOffset()) {
                private int codeAddress = 0;
                private int lineNumber = lineNumberStart;

                @Nullable
                protected DebugItem readNextItem(@Nonnull DexReader<? extends DexBuffer> reader) {
                    while (codeAddress <= lastInstructionAddress) {
                        int next = reader.readUbyte();
                        switch (next) {
                            case DebugItemType.END_SEQUENCE: {
                                return endOfData();
                            }
                            case DebugItemType.ADVANCE_PC: {
                                int addressDiff = reader.readSmallUleb128();
                                codeAddress += addressDiff;
                                continue;
                            }
                            case DebugItemType.ADVANCE_LINE: {
                                int lineDiff = reader.readSleb128();
                                lineNumber += lineDiff;
                                continue;
                            }
                            case DebugItemType.START_LOCAL: {
                                int register = reader.readSmallUleb128();
                                String name = dexFile.getStringSection().getOptional(reader.readSmallUleb128() - 1);
                                String type = dexFile.getTypeSection().getOptional(reader.readSmallUleb128() - 1);
                                ImmutableStartLocal startLocal =
                                        new ImmutableStartLocal(codeAddress, register, name, type, null);
                                if (register >= 0 && register < locals.length) {
                                    locals[register] = startLocal;
                                }
                                return startLocal;
                            }
                            case DebugItemType.START_LOCAL_EXTENDED: {
                                int register = reader.readSmallUleb128();
                                String name = dexFile.getStringSection().getOptional(reader.readSmallUleb128() - 1);
                                String type = dexFile.getTypeSection().getOptional(reader.readSmallUleb128() - 1);
                                String signature = dexFile.getStringSection().getOptional(
                                        reader.readSmallUleb128() - 1);
                                ImmutableStartLocal startLocal =
                                        new ImmutableStartLocal(codeAddress, register, name, type, signature);
                                if (register >= 0 && register < locals.length) {
                                    locals[register] = startLocal;
                                }
                                return startLocal;
                            }
                            case DebugItemType.END_LOCAL: {
                                int register = reader.readSmallUleb128();

                                boolean replaceLocalInTable = true;
                                LocalInfo localInfo;
                                if (register >= 0 && register < locals.length) {
                                    localInfo = locals[register];
                                } else {
                                    localInfo = EMPTY_LOCAL_INFO;
                                    replaceLocalInTable = false;
                                }

                                if (localInfo instanceof EndLocal) {
                                    localInfo = EMPTY_LOCAL_INFO;
                                    // don't replace the local info in locals. The new EndLocal won't have any info at all,
                                    // and we dont want to wipe out what's there, so that it is available for a subsequent
                                    // RestartLocal
                                    replaceLocalInTable = false;
                                }
                                ImmutableEndLocal endLocal =
                                        new ImmutableEndLocal(codeAddress, register, localInfo.getName(),
                                                localInfo.getType(), localInfo.getSignature());
                                if (replaceLocalInTable) {
                                    locals[register] = endLocal;
                                }
                                return endLocal;
                            }
                            case DebugItemType.RESTART_LOCAL: {
                                int register = reader.readSmallUleb128();
                                LocalInfo localInfo;
                                if (register >= 0 && register < locals.length) {
                                    localInfo = locals[register];
                                } else {
                                    localInfo = EMPTY_LOCAL_INFO;
                                }
                                ImmutableRestartLocal restartLocal =
                                        new ImmutableRestartLocal(codeAddress, register, localInfo.getName(),
                                                localInfo.getType(), localInfo.getSignature());
                                if (register >= 0 && register < locals.length) {
                                    locals[register] = restartLocal;
                                }
                                return restartLocal;
                            }
                            case DebugItemType.PROLOGUE_END: {
                                return new ImmutablePrologueEnd(codeAddress);
                            }
                            case DebugItemType.EPILOGUE_BEGIN: {
                                return new ImmutableEpilogueBegin(codeAddress);
                            }
                            case DebugItemType.SET_SOURCE_FILE: {
                                String sourceFile = dexFile.getStringSection().getOptional(
                                        reader.readSmallUleb128() - 1);
                                return new ImmutableSetSourceFile(codeAddress, sourceFile);
                            }
                            default: {
                                int adjusted = next - 0x0A;
                                codeAddress += adjusted / 15;
                                lineNumber += (adjusted % 15) - 4;
                                if (codeAddress > lastInstructionAddress) {
                                  return endOfData();
                                }
                                return new ImmutableLineNumber(codeAddress, lineNumber);
                            }
                        }
                    }
                    return endOfData();
                }
            };
        }

        @Nonnull
        @Override
        public VariableSizeIterator<String> getParameterNames(@Nullable DexReader<? extends DexBuffer> reader) {
            if (reader == null) {
                reader = dexFile.getDataBuffer().readerAt(debugInfoOffset);
                reader.skipUleb128();
            }
            //TODO: make sure dalvik doesn't allow more parameter names than we have parameters
            final int parameterNameCount = reader.readSmallUleb128();
            return new VariableSizeIterator<String>(reader, parameterNameCount) {
                @Override protected String readNextItem(@Nonnull DexReader<? extends DexBuffer> reader, int index) {
                    return dexFile.getStringSection().getOptional(reader.readSmallUleb128() - 1);
                }
            };
        }

        @Override
        public int getSize() {
            Iterator<DebugItem> iter = iterator();
            while(iter.hasNext()) {
                iter.next();
            }
            return ((VariableSizeLookaheadIterator) iter).getReaderOffset() - debugInfoOffset;
        }
    }
}
