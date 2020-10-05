/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.graal.python.runtime;

import com.oracle.graal.python.nodes.PRaiseNode;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import org.graalvm.nativeimage.ImageInfo;
import org.graalvm.nativeimage.ProcessProperties;

import com.oracle.graal.python.PythonLanguage;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.library.GenerateLibrary;
import com.oracle.truffle.api.library.Library;
import com.oracle.truffle.api.source.Source;

import static com.oracle.graal.python.runtime.exception.PythonErrorType.NotImplementedError;

@GenerateLibrary
public abstract class PosixSupportLibrary extends Library {
    public abstract long getpid(Object receiver);
    public abstract long umask(Object receiver, long mask);
    public abstract int open(Object receiver, String pathname, int flags);
    public abstract int close(Object receiver, int fd);
    public abstract long read(Object receiver, int fd, byte[] buf);

    @ExportLibrary(PosixSupportLibrary.class)
    public static final class EmulatedPosixSupport {
        @ExportMessage
        @ImportStatic(ImageInfo.class)
        public static class Getpid {
            @Specialization(guards = "inImageRuntimeCode()")
            static long inNativeImage(EmulatedPosixSupport receiver) {
                return ProcessProperties.getProcessID();
            }

            @Specialization(guards = "!inImageRuntimeCode()", rewriteOn = Exception.class)
            static long usingProc(EmulatedPosixSupport receiver,
                            @CachedContext(PythonLanguage.class) ContextReference<PythonContext> ctxRef) throws Exception {
                TruffleFile statFile = ctxRef.get().getPublicTruffleFileRelaxed("/proc/self/stat");
                return Long.parseLong(new String(statFile.readAllBytes()).trim().split(" ")[0]);
            }

            @Specialization(guards = "!inImageRuntimeCode()", replaces = "usingProc")
            static long usingMXBean(EmulatedPosixSupport receiver,
                            @CachedContext(PythonLanguage.class) ContextReference<PythonContext> ctxRef) {
                String info = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
                return Long.parseLong(info.split("@")[0]);
            }
        }

        @ExportMessage
        public long umask(long umask,
                        @Cached PRaiseNode raiseNode) { // TODO get the raise node only if actually needed like getRaiseNode() in PythonBuiltinBaseNode / specialize on the value of umask
            if (umask == 0022) {
                return 0022;
            }
            if (umask == 0) {
                // TODO: change me, this does not really set the umask, workaround needed for pip
                // it returns the previous mask (which in our case is always 0022)
                return 0022;
            } else {
                throw raiseNode.raise(NotImplementedError, "setting the umask to anything other than the default");
            }
        }

        @ExportMessage
        public int open(String pathname, int flags) {
            throw CompilerDirectives.shouldNotReachHere("Not implemented");
        }

        @ExportMessage
        public int close(int fd) {
            throw CompilerDirectives.shouldNotReachHere("Not implemented");
        }

        @ExportMessage
        public long read(int fd, byte[] buf) {
            throw CompilerDirectives.shouldNotReachHere("Not implemented");
        }

    }

    @ExportLibrary(PosixSupportLibrary.class)
    public abstract static class NativePosixSupportBase {

        private volatile Object library;
        private volatile Object getpidFunction;
        private volatile Object umaskFunction;
        private volatile Object openFunction;
        private volatile Object closeFunction;
        private volatile Object readFunction;

        @ExportMessage
        public long getpid(
                    @Shared("fun") @CachedLibrary(limit = "1") InteropLibrary funInterop,
                    @Shared("res") @CachedLibrary(limit = "1") InteropLibrary resultInterop) {
            if (getpidFunction == null) {
                CompilerDirectives.transferToInterpreter();
                getpidFunction = lookup("call_getpid");
            }
            return callLong(funInterop, resultInterop, getpidFunction);
        }

        @ExportMessage
        public long umask(long mask,
                    @Shared("fun") @CachedLibrary(limit = "1") InteropLibrary funInterop,
                    @Shared("res") @CachedLibrary(limit = "1") InteropLibrary resultInterop) {
            if (umaskFunction == null) {
                CompilerDirectives.transferToInterpreter();
                umaskFunction = lookup("call_umask");
            }
            long result = callLong(funInterop, resultInterop, umaskFunction, mask);
            if (result < 0) {
                // TODO call errno() and raise OS error
                // create helper method for this (like CPython's  posix_error)
            }
            return result;
        }

        @ExportMessage
        public int open(String pathname, int flags,
                    @Shared("fun") @CachedLibrary(limit = "1") InteropLibrary funInterop,
                    @Shared("res") @CachedLibrary(limit = "1") InteropLibrary resultInterop) {
            if (openFunction == null) {
                CompilerDirectives.transferToInterpreter();
                openFunction = lookup("call_open");
            }
            // TODO error handling
            return callInt(funInterop, resultInterop, openFunction, pathname, flags);
        }

        @ExportMessage
        public int close(int fd,
                    @Shared("fun") @CachedLibrary(limit = "1") InteropLibrary funInterop,
                    @Shared("res") @CachedLibrary(limit = "1") InteropLibrary resultInterop) {
            if (closeFunction == null) {
                CompilerDirectives.transferToInterpreter();
                closeFunction = lookup("call_close");
            }
            // TODO error handling
            return callInt(funInterop, resultInterop, closeFunction, fd);
        }

        @ExportMessage
        public long read(int fd, byte[] buf,
                    @Shared("fun") @CachedLibrary(limit = "1") InteropLibrary funInterop,
                    @Shared("res") @CachedLibrary(limit = "1") InteropLibrary resultInterop) {
            if (readFunction == null) {
                CompilerDirectives.transferToInterpreter();
                readFunction = lookup("call_read");
            }
            // TODO error handling
            return callLong(funInterop, resultInterop, readFunction, fd, PythonLanguage.getContext().getEnv().asGuestValue(buf), buf.length);
        }

        protected abstract Object loadLibrary(PythonContext ctxRef);

        public Object ensureLibrary() {
            if (library == null) {
                // This should happen once per Python context, because there should be one
                // PosixSupportLibrary instance per context
                CompilerDirectives.transferToInterpreter();
                synchronized (this) {
                    library = loadLibrary(PythonLanguage.getContext());
                }
            }
            return library;
        }

        protected Object lookup(String symbolName) {
            try {
                return InteropLibrary.getUncached().readMember(ensureLibrary(), symbolName);
            } catch (InteropException e) {
                throw CompilerDirectives.shouldNotReachHere(e);
            }
        }

        protected long callLong(InteropLibrary funInterop, InteropLibrary resInterop, Object fun, Object... args) {
            try {
                Object result = funInterop.execute(fun, args);
                return resInterop.asLong(result);
            } catch (UnsupportedMessageException | UnsupportedTypeException | ArityException e) {
                throw CompilerDirectives.shouldNotReachHere(e);
            }
        }

        protected int callInt(InteropLibrary funInterop, InteropLibrary resInterop, Object fun, Object... args) {
            try {
                Object result = funInterop.execute(fun, args);
                return resInterop.asInt(result);
            } catch (UnsupportedMessageException | UnsupportedTypeException | ArityException e) {
                throw CompilerDirectives.shouldNotReachHere(e);
            }
        }
    }

    @ExportLibrary(PosixSupportLibrary.class)
    public static final class NFIPosixSupport extends NativePosixSupportBase {
        private final String backend;

        public NFIPosixSupport(String backend) {
            this.backend = backend;
        }

        public static NFIPosixSupport createNative() {
            return new NFIPosixSupport(null);
        }

        public static NFIPosixSupport createLLVM() {
            return new NFIPosixSupport("llvm");
        }

        @Override
        protected Object loadLibrary(PythonContext ctx) {
            String withClause = backend == null ? "" : "with " + backend;
            Env env = ctx.getEnv();
            // Load the support library
            String src = String.format("%s load (RTLD_GLOBAL) \"%s\"", withClause, getSupportLibraryPath());
            Source loadSupportSource = Source.newBuilder("nfi", src, "load-posix-support").build();
            env.parseInternal(loadSupportSource).call();
            // Now the default should contain symbols from both the support library and libc with
            // which it links
            Source loadDefaultSource = Source.newBuilder("nfi", withClause + " default { call_getpid():sint64; call_umask(sint64):sint64; call_open(string, sint32):sint32; call_close(sint32):sint32; call_read(sint32, [sint8], uint64):sint64; }", "load-posix-support-default-lib").build();
            return env.parseInternal(loadDefaultSource).call();
        }

        private static String getSupportLibraryPath() {
            // TODO: hard-coded for now
            return "/home/otethal/graalvm/graalpython/posix/graalvm-posix-support.so";
        }
    }
}
