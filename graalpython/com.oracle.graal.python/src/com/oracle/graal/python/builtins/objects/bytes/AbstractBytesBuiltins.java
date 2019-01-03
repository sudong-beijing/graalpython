/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.graal.python.builtins.objects.bytes;

import com.oracle.graal.python.builtins.Builtin;
import com.oracle.graal.python.builtins.CoreFunctions;
import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.PythonBuiltins;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.ints.PInt;
import com.oracle.graal.python.builtins.objects.list.ListBuiltins.ListAppendNode;
import com.oracle.graal.python.builtins.objects.list.PList;
import com.oracle.graal.python.nodes.function.PythonBuiltinBaseNode;
import com.oracle.graal.python.nodes.function.builtins.PythonBinaryBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonTernaryBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonUnaryBuiltinNode;
import com.oracle.graal.python.nodes.truffle.PythonArithmeticTypes;
import com.oracle.graal.python.nodes.util.CastToIntegerFromIndexNode;
import com.oracle.graal.python.runtime.exception.PythonErrorType;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.NodeFactory;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.ConditionProfile;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@CoreFunctions(extendClasses = {PythonBuiltinClassType.PByteArray, PythonBuiltinClassType.PBytes})
public class AbstractBytesBuiltins extends PythonBuiltins {

    @Override
    protected List<? extends NodeFactory<? extends PythonBuiltinBaseNode>> getNodeFactories() {
        return AbstractBytesBuiltinsFactory.getFactories();
    }

    @Builtin(name = "lower", fixedNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class LowerNode extends PythonUnaryBuiltinNode {
        @Node.Child private BytesNodes.ToBytesNode toBytes = BytesNodes.ToBytesNode.create();

        @CompilerDirectives.TruffleBoundary
        private static byte[] lower(byte[] bytes) {
            try {
                String string = new String(bytes, "ASCII");
                return string.toLowerCase().getBytes("ASCII");
            } catch (UnsupportedEncodingException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException();
            }
        }

        @Specialization
        PByteArray replace(PByteArray self) {
            return factory().createByteArray(lower(toBytes.execute(self)));
        }

        @Specialization
        PBytes replace(PBytes self) {
            return factory().createBytes(lower(toBytes.execute(self)));
        }
    }

    @Builtin(name = "upper", fixedNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class UpperNode extends PythonUnaryBuiltinNode {
        @Node.Child private BytesNodes.ToBytesNode toBytes = BytesNodes.ToBytesNode.create();

        @CompilerDirectives.TruffleBoundary
        private static byte[] upper(byte[] bytes) {
            try {
                String string = new String(bytes, "ASCII");
                return string.toUpperCase().getBytes("ASCII");
            } catch (UnsupportedEncodingException e) {
                CompilerDirectives.transferToInterpreter();
                throw new IllegalStateException();
            }
        }

        @Specialization
        PByteArray replace(PByteArray self) {
            return factory().createByteArray(upper(toBytes.execute(self)));
        }

        @Specialization
        PBytes replace(PBytes self) {
            return factory().createBytes(upper(toBytes.execute(self)));
        }
    }

    abstract static class AStripNode extends PythonBinaryBuiltinNode {
        int mod() {
            throw new RuntimeException();
        }

        int stop(@SuppressWarnings("unused") byte[] bs) {
            throw new RuntimeException();
        }

        int start(@SuppressWarnings("unused") byte[] bs) {
            throw new RuntimeException();
        }

        PByteArray newByteArrayFrom(@SuppressWarnings("unused") byte[] bs, @SuppressWarnings("unused") int i) {
            throw new RuntimeException();
        }

        PBytes newBytesFrom(@SuppressWarnings("unused") byte[] bs, @SuppressWarnings("unused") int i) {
            throw new RuntimeException();
        }

        private int findIndex(byte[] bs) {
            int i = start(bs);
            int stop = stop(bs);
            for (; i != stop; i += mod()) {
                if (!isWhitespace(bs[i])) {
                    break;
                }
            }
            return i;
        }

        @Specialization
        PByteArray strip(PByteArray self, @SuppressWarnings("unused") PNone bytes,
                        @Cached("create()") BytesNodes.ToBytesNode toBytesNode) {
            byte[] bs = toBytesNode.execute(self);
            return newByteArrayFrom(bs, findIndex(bs));
        }

        @Specialization
        PBytes strip(PBytes self, @SuppressWarnings("unused") PNone bytes,
                        @Cached("create()") BytesNodes.ToBytesNode toBytesNode) {
            byte[] bs = toBytesNode.execute(self);
            return newBytesFrom(bs, findIndex(bs));
        }

        @CompilerDirectives.TruffleBoundary
        private static boolean isWhitespace(byte b) {
            return Character.isWhitespace(b);
        }

        private int findIndex(byte[] bs, byte[] stripBs) {
            int i = start(bs);
            int stop = stop(bs);
            outer: for (; i != stop; i += mod()) {
                for (byte b : stripBs) {
                    if (b == bs[i]) {
                        continue outer;
                    }
                }
                break;
            }
            return i;
        }

        @Specialization
        PByteArray strip(PByteArray self, PBytes bytes,
                        @Cached("create()") BytesNodes.ToBytesNode selfToBytesNode,
                        @Cached("create()") BytesNodes.ToBytesNode otherToBytesNode) {
            byte[] stripBs = selfToBytesNode.execute(bytes);
            byte[] bs = otherToBytesNode.execute(self);
            return newByteArrayFrom(bs, findIndex(bs, stripBs));
        }

        @Specialization
        PBytes strip(PBytes self, PBytes bytes,
                        @Cached("create()") BytesNodes.ToBytesNode selfToBytesNode,
                        @Cached("create()") BytesNodes.ToBytesNode otherToBytesNode) {
            byte[] stripBs = selfToBytesNode.execute(bytes);
            byte[] bs = otherToBytesNode.execute(self);
            return newBytesFrom(bs, findIndex(bs, stripBs));
        }

    }

    @Builtin(name = "lstrip", minNumOfPositionalArgs = 1, maxNumOfPositionalArgs = 2, keywordArguments = {"bytes"})
    @GenerateNodeFactory
    abstract static class LStripNode extends AStripNode {

        private static byte[] getResultBytes(int i, byte[] bs) {
            byte[] out;
            if (i != 0) {
                int len = bs.length - i;
                out = new byte[len];
                System.arraycopy(bs, i, out, 0, len);
            } else {
                out = bs;
            }
            return out;
        }

        @Override
        PByteArray newByteArrayFrom(byte[] bs, int i) {
            return factory().createByteArray(getResultBytes(i, bs));
        }

        @Override
        PBytes newBytesFrom(byte[] bs, int i) {
            return factory().createBytes(getResultBytes(i, bs));
        }

        @Override
        int mod() {
            return 1;
        }

        @Override
        int stop(byte[] bs) {
            return bs.length;
        }

        @Override
        int start(byte[] bs) {
            return 0;
        }
    }

    @Builtin(name = "rstrip", minNumOfPositionalArgs = 1, maxNumOfPositionalArgs = 2, keywordArguments = {"bytes"})
    @GenerateNodeFactory
    abstract static class RStripNode extends AStripNode {

        private static byte[] getResultBytes(int i, byte[] bs) {
            byte[] out;
            int len = i + 1;
            if (len != bs.length) {
                out = new byte[len];
                System.arraycopy(bs, 0, out, 0, len);
            } else {
                out = bs;
            }
            return out;
        }

        @Override
        PByteArray newByteArrayFrom(byte[] bs, int i) {
            byte[] out = getResultBytes(i, bs);
            return factory().createByteArray(out);
        }

        @Override
        PBytes newBytesFrom(byte[] bs, int i) {
            byte[] out = getResultBytes(i, bs);
            return factory().createBytes(out);
        }

        @Override
        int mod() {
            return -1;
        }

        @Override
        int stop(byte[] bs) {
            return -1;
        }

        @Override
        int start(byte[] bs) {
            return bs.length - 1;
        }
    }

    abstract static class AbstractSplitNode extends PythonTernaryBuiltinNode {

        @SuppressWarnings("unused")
        protected List<byte[]> splitWhitespace(byte[] bytes, int maxsplit) {
            throw new RuntimeException();
        }

        @SuppressWarnings("unused")
        protected List<byte[]> splitDelimiter(byte[] bytes, byte[] sep, int maxsplit) {
            throw new RuntimeException();
        }

        @CompilationFinal private ConditionProfile isEmptySepProfile;
        @CompilationFinal private ConditionProfile overflowProfile;

        @Child private BytesNodes.ToBytesNode selfToBytesNode;
        @Child private BytesNodes.ToBytesNode sepToBytesNode;
        @Child private ListAppendNode appendNode;
        @Child private CastToIntegerFromIndexNode castIntNode;
        @Child private SplitNode recursiveNode;

        // taken from JPython
        private static final int SWAP_CASE = 0x20;
        private static final byte UPPER = 0b1;
        private static final byte LOWER = 0b10;
        private static final byte DIGIT = 0b100;
        private static final byte SPACE = 0b1000;
        private static final byte[] CTYPE = new byte[256];

        static {
            for (int c = 'A'; c <= 'Z'; c++) {
                CTYPE[0x80 + c] = UPPER;
                CTYPE[0x80 + SWAP_CASE + c] = LOWER;
            }
            for (int c = '0'; c <= '9'; c++) {
                CTYPE[0x80 + c] = DIGIT;
            }
            for (char c : " \t\n\u000b\f\r".toCharArray()) {
                CTYPE[0x80 + c] = SPACE;
            }
        }

        private ConditionProfile getIsEmptyProfile() {
            if (isEmptySepProfile == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                isEmptySepProfile = ConditionProfile.createBinaryProfile();
            }
            return isEmptySepProfile;
        }

        private ConditionProfile getOverflowProfile() {
            if (overflowProfile == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                overflowProfile = ConditionProfile.createBinaryProfile();
            }
            return overflowProfile;
        }

        protected BytesNodes.ToBytesNode getSelfToBytesNode() {
            if (selfToBytesNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                selfToBytesNode = insert(BytesNodes.ToBytesNode.create());
            }
            return selfToBytesNode;
        }

        protected BytesNodes.ToBytesNode getSepToBytesNode() {
            if (sepToBytesNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                sepToBytesNode = insert(BytesNodes.ToBytesNode.create());
            }
            return sepToBytesNode;
        }

        protected ListAppendNode getAppendNode() {
            if (appendNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                appendNode = insert(ListAppendNode.create());
            }
            return appendNode;
        }

        private CastToIntegerFromIndexNode getCastIntNode() {
            if (castIntNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                castIntNode = insert(CastToIntegerFromIndexNode.create());
            }
            return castIntNode;
        }

        private SplitNode getRecursiveNode() {
            if (recursiveNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                recursiveNode = insert(AbstractBytesBuiltinsFactory.SplitNodeFactory.create());
            }
            return recursiveNode;
        }

        private int getIntValue(PInt from) {
            try {
                return from.intValueExact();
            } catch (ArithmeticException e) {
                throw raise(PythonErrorType.OverflowError, "Python int too large to convert to C long");
            }
        }

        private int getIntValue(long from) {
            if (getOverflowProfile().profile(Integer.MIN_VALUE > from || from > Integer.MAX_VALUE)) {
                throw raise(PythonErrorType.OverflowError, "Python int too large to convert to C long");
            }
            return (int) from;
        }

        private PList getBytesResult(List<byte[]> bytes) {
            PList result = factory().createList();
            for (byte[] bs : bytes) {
                getAppendNode().execute(result, factory().createBytes(bs));
            }
            return result;
        }

        private PList getByteArrayResult(List<byte[]> bytes) {
            PList result = factory().createList();
            for (byte[] bs : bytes) {
                getAppendNode().execute(result, factory().createByteArray(bs));
            }
            return result;
        }

        @TruffleBoundary
        protected static byte[] copyOfRange(byte[] bytes, int from, int to) {
            return Arrays.copyOfRange(bytes, from, to);
        }

        protected static boolean isSpace(byte b) {
            return (CTYPE[0x80 + b] & SPACE) != 0;
        }

        // split()
        // rsplit()
        @Specialization
        PList split(Object bytes, @SuppressWarnings("unused") PNone sep, @SuppressWarnings("unused") PNone maxsplit) {
            byte[] splitBs = getSelfToBytesNode().execute(bytes);
            if (bytes instanceof PByteArray) {
                return getByteArrayResult(splitWhitespace(splitBs, -1));
            }
            return getBytesResult(splitWhitespace(splitBs, -1));
        }

        // split(sep=...)
        // rsplit(sep=...)
        @Specialization(guards = "!isPNone(sep)")
        PList split(Object bytes, Object sep, @SuppressWarnings("unused") PNone maxsplit) {
            return split(bytes, sep, -1);
        }

        // split(sep=..., maxsplit=...)
        // rsplit(sep=..., maxsplit=...)
        @Specialization(guards = "!isPNone(sep)")
        PList split(Object bytes, Object sep, int maxsplit) {
            byte[] sepBs = getSepToBytesNode().execute(sep);
            if (getIsEmptyProfile().profile(sepBs.length == 0)) {
                throw raise(PythonErrorType.ValueError, "empty separator");
            }
            byte[] splitBs = getSelfToBytesNode().execute(bytes);
            if (bytes instanceof PByteArray) {
                return getByteArrayResult(splitDelimiter(splitBs, sepBs, maxsplit));
            }
            return getBytesResult(splitDelimiter(splitBs, sepBs, maxsplit));
        }

        @Specialization(guards = "!isPNone(sep)")
        PList split(Object bytes, Object sep, long maxsplit) {
            return split(bytes, sep, getIntValue(maxsplit));
        }

        @Specialization(guards = "!isPNone(sep)")
        PList split(Object bytes, Object sep, PInt maxsplit) {
            return split(bytes, sep, getIntValue(maxsplit));
        }

        @Specialization(guards = "!isPNone(sep)")
        PList split(Object bytes, Object sep, Object maxsplit) {
            return (PList) getRecursiveNode().execute(bytes, sep, getCastIntNode().execute(maxsplit));
        }

        // split(maxsplit=...)
        // rsplit(maxsplit=...)
        @Specialization
        PList split(Object bytes, @SuppressWarnings("unused") PNone sep, int maxsplit) {
            byte[] splitBs = getSelfToBytesNode().execute(bytes);
            if (bytes instanceof PByteArray) {
                return getByteArrayResult(splitWhitespace(splitBs, maxsplit));
            }
            return getBytesResult(splitWhitespace(splitBs, maxsplit));
        }

        @Specialization
        PList split(Object bytes, PNone sep, long maxsplit) {
            return split(bytes, sep, getIntValue(maxsplit));
        }

        @Specialization
        PList split(Object bytes, PNone sep, PInt maxsplit) {
            return split(bytes, sep, getIntValue(maxsplit));
        }

        @Specialization
        PList split(Object bytes, PNone sep, Object maxsplit) {
            return (PList) getRecursiveNode().execute(bytes, sep, getCastIntNode().execute(maxsplit));
        }

    }

    @Builtin(name = "split", minNumOfPositionalArgs = 1, maxNumOfPositionalArgs = 3, keywordArguments = {"sep", "maxsplit"})
    @GenerateNodeFactory
    @TypeSystemReference(PythonArithmeticTypes.class)
    abstract static class SplitNode extends AbstractSplitNode {

        @Override
        protected List<byte[]> splitWhitespace(byte[] bytes, int maxsplit) {
            int offset = 0;
            int size = bytes.length;

            List<byte[]> result = new ArrayList<>();
            if (size == 0) {
                return result;
            }
            if (maxsplit == 0) {
                // handling case b''.split(b' ') -> [b'']
                result.add(bytes);
                return result;
            }
            int p, q; // Indexes of unsplit text and whitespace

            // Scan over leading whitespace
            for (p = offset; p < size && isSpace(bytes[p]); p++) {
            }

            // At this point if p<limit it points to the start of a word.
            // While we have some splits left (if maxsplit started>=0)
            while (p < size && maxsplit-- != 0) {
                // Delimit a word at p
                // Skip q over the non-whitespace at p
                for (q = p; q < size && !isSpace(bytes[q]); q++) {
                }
                // storage[q] is whitespace or it is at the limit
                result.add(copyOfRange(bytes, p - offset, q - offset));
                // Skip p over the whitespace at q
                for (p = q; p < size && isSpace(bytes[p]); p++) {
                }
            }

            // Append the remaining unsplit text if any
            if (p < size) {
                result.add(copyOfRange(bytes, p - offset, size));
            }
            return result;
        }

        @Override
        protected List<byte[]> splitDelimiter(byte[] bytes, byte[] sep, int maxsplit) {
            List<byte[]> result = new ArrayList<>();
            int size = bytes.length;

            if (maxsplit == 0 || size == 0) {
                // if maxsplit is 0, just add the whole input
                result.add(bytes);
                return result;
            }
            if (sep.length == 0) {
                // should not happen, and should be threated outside this method
                return result;
            }
            int begin = 0;

            outer: for (int offset = 0; offset < size - sep.length + 1; offset++) {
                for (int sepOffset = 0; sepOffset < sep.length; sepOffset++) {
                    if (bytes[offset + sepOffset] != sep[sepOffset]) {
                        continue outer;
                    }
                }

                if (begin < offset) {
                    result.add(copyOfRange(bytes, begin, offset));
                } else {
                    result.add(new byte[0]);
                }
                begin = offset + sep.length;
                offset = begin - 1;
                if (--maxsplit == 0) {
                    break;
                }
            }

            if (begin != size) {
                result.add(copyOfRange(bytes, begin, size));
            }
            return result;
        }
    }

    @Builtin(name = "rsplit", minNumOfPositionalArgs = 1, maxNumOfPositionalArgs = 3, keywordArguments = {"sep", "maxsplit"})
    @GenerateNodeFactory
    @TypeSystemReference(PythonArithmeticTypes.class)
    abstract static class RSplitNode extends AbstractSplitNode {

        @Override
        protected List<byte[]> splitWhitespace(byte[] bytes, int maxsplit) {
            int size = bytes.length;
            List<byte[]> result = new ArrayList<>();

            if (size == 0) {
                return result;
            }

            if (maxsplit == 0) {
                // if maxsplit is 0, just add the whole input
                result.add(bytes);
                return result;
            }

            int offset = 0;

            int p, q; // Indexes of unsplit text and whitespace

            // Scan backwards over trailing whitespace
            for (q = offset + size; q > offset; --q) {
                if (!isSpace(bytes[q - 1])) {
                    break;
                }
            }

            // At this point storage[q-1] is the rightmost non-space byte, or
            // q=offset if there aren't any. While we have some splits left ...
            while (q > offset && maxsplit-- != 0) {
                // Delimit the word whose last byte is storage[q-1]
                // Skip p backwards over the non-whitespace
                for (p = q; p > offset; --p) {
                    if (isSpace(bytes[p - 1])) {
                        break;
                    }
                }

                result.add(0, copyOfRange(bytes, p - offset, q - offset));
                // Skip q backwards over the whitespace
                for (q = p; q > offset; --q) {
                    if (!isSpace(bytes[q - 1])) {
                        break;
                    }
                }
            }

            // Prepend the remaining unsplit text if any
            if (q > offset) {
                result.add(0, copyOfRange(bytes, 0, q - offset));
            }
            return result;
        }

        @Override
        protected List<byte[]> splitDelimiter(byte[] bytes, byte[] sep, int maxsplit) {
            List<byte[]> result = new ArrayList<>();
            int size = bytes.length;

            if (maxsplit == 0 || size == 0) {
                // if maxsplit is 0, just add the whole input
                result.add(bytes);
                return result;
            }
            if (sep.length == 0) {
                // should not happen, and should be threated outside this method
                return result;
            }
            int end = size;

            outer: for (int offset = size - 1; offset >= 0; offset--) {
                for (int sepOffset = 0; sepOffset < sep.length; sepOffset++) {
                    if (bytes[offset - sepOffset] != sep[sep.length - sepOffset - 1]) {
                        continue outer;
                    }
                }

                if (end > offset) {
                    result.add(0, copyOfRange(bytes, offset + 1, end));
                } else {
                    result.add(0, new byte[0]);
                }
                end = offset;
                if (--maxsplit == 0) {
                    break;
                }
            }

            if (end != 0) {
                result.add(0, copyOfRange(bytes, 0, end));
            }
            return result;
        }
    }
}
