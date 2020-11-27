/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.python.builtins.objects.object;

import static com.oracle.graal.python.builtins.PythonBuiltinClassType.SystemError;
import static com.oracle.graal.python.builtins.PythonBuiltinClassType.ValueError;
import static com.oracle.graal.python.nodes.ErrorMessages.ATTR_NAME_MUST_BE_STRING;
import static com.oracle.graal.python.nodes.ErrorMessages.BAD_ARG_TO_INTERNAL_FUNC;
import static com.oracle.graal.python.nodes.ErrorMessages.CANNOT_PICKLE_OBJECT_TYPE;
import static com.oracle.graal.python.nodes.ErrorMessages.COPYREG_SLOTNAMES;
import static com.oracle.graal.python.nodes.ErrorMessages.MUST_BE_TYPE_A_NOT_TYPE_B;
import static com.oracle.graal.python.nodes.ErrorMessages.SHOULD_RETURN_A_NOT_B;
import static com.oracle.graal.python.nodes.ErrorMessages.SHOULD_RETURN_TYPE_A_NOT_TYPE_B;
import static com.oracle.graal.python.nodes.ErrorMessages.SLOTNAMES_SHOULD_BE_A_NOT_B;
import static com.oracle.graal.python.nodes.SpecialAttributeNames.__CLASS__;
import static com.oracle.graal.python.nodes.SpecialAttributeNames.__NEWOBJ_EX__;
import static com.oracle.graal.python.nodes.SpecialAttributeNames.__NEWOBJ__;
import static com.oracle.graal.python.nodes.SpecialAttributeNames.__SLOTNAMES__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.ITEMS;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__GETNEWARGS_EX__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__GETNEWARGS__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__GETSTATE__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.__NEW__;
import static com.oracle.graal.python.runtime.exception.PythonErrorType.TypeError;

import com.oracle.truffle.api.profiles.ConditionProfile;
import org.graalvm.collections.Pair;

import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.common.HashingStorageLibrary;
import com.oracle.graal.python.builtins.objects.common.SequenceNodes;
import com.oracle.graal.python.builtins.objects.common.SequenceStorageNodes;
import com.oracle.graal.python.builtins.objects.dict.PDict;
import com.oracle.graal.python.builtins.objects.type.TypeNodes;
import com.oracle.graal.python.nodes.PGuards;
import com.oracle.graal.python.nodes.PNodeWithState;
import com.oracle.graal.python.nodes.object.IsBuiltinClassProfile;
import com.oracle.graal.python.nodes.statement.ImportNode;
import com.oracle.graal.python.nodes.util.CannotCastException;
import com.oracle.graal.python.nodes.util.CastToJavaStringNode;
import com.oracle.graal.python.runtime.PythonOptions;
import com.oracle.graal.python.runtime.sequence.storage.SequenceStorage;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.Node;

public abstract class ObjectNodes {

    @ImportStatic({PythonOptions.class, PGuards.class})
    abstract static class BaseReduceNode extends PNodeWithState {
        protected static final String MOD_COPYREG = "copyreg";

        @Child ImportNode.ImportExpression importExpression;

        protected static boolean isDict(Object object, IsBuiltinClassProfile profile) {
            return profile.profileObject(object, PythonBuiltinClassType.PDict);
        }

        protected static boolean isTuple(Object object, IsBuiltinClassProfile profile) {
            return profile.profileObject(object, PythonBuiltinClassType.PTuple);
        }

        protected static boolean isList(Object object, IsBuiltinClassProfile profile) {
            return profile.profileObject(object, PythonBuiltinClassType.PList);
        }

        protected Object importCopyReg(VirtualFrame frame) {
            if (importExpression == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                importExpression = insert(ImportNode.createAsExpression(MOD_COPYREG));
            }
            return importExpression.execute(frame);
        }
    }

    @ImportStatic({PythonOptions.class, PGuards.class})
    abstract static class GetNewArgsDispatchNode extends Node {
        public abstract Pair<Object, Object> execute(VirtualFrame frame, Object obj);

        @Specialization(limit = "getCallSiteInlineCacheMaxDepth()")
        Pair<Object, Object> dispatch(VirtualFrame frame, Object obj,
                        @Cached GetNewArgsNode getNewArgsNode,
                        @CachedLibrary(value = "obj") PythonObjectLibrary pol) {
            Object getNewArgsExAttr = pol.lookupAttribute(obj, frame, __GETNEWARGS_EX__);
            Object getNewArgsAttr = pol.lookupAttribute(obj, frame, __GETNEWARGS__);
            return getNewArgsNode.execute(frame, getNewArgsExAttr, getNewArgsAttr);
        }
    }

    abstract static class GetNewArgsNode extends BaseReduceNode {
        public abstract Pair<Object, Object> execute(VirtualFrame frame, Object getNewArgsExAttr, Object getNewArgsAttr);

        @Specialization(guards = "!isNoValue(getNewArgsExAttr)")
        Pair<Object, Object> doNewArgsEx(VirtualFrame frame, Object getNewArgsExAttr, @SuppressWarnings("unused") Object getNewArgsAttr,
                        @Cached IsBuiltinClassProfile newargsProfile,
                        @Cached IsBuiltinClassProfile argsProfile,
                        @Cached IsBuiltinClassProfile kwargsProfile,
                        @Cached SequenceStorageNodes.GetItemNode getItemNode,
                        @Cached SequenceNodes.GetSequenceStorageNode getSequenceStorageNode,
                        @CachedLibrary(limit = "getCallSiteInlineCacheMaxDepth()") PythonObjectLibrary pol) {
            Object newargs = pol.callObject(getNewArgsExAttr, frame);
            if (!isTuple(newargs, newargsProfile)) {
                throw raise(TypeError, SHOULD_RETURN_TYPE_A_NOT_TYPE_B, __GETNEWARGS_EX__, "tuple", newargs);
            }
            int length = pol.length(newargs);
            if (length != 2) {
                throw raise(ValueError, SHOULD_RETURN_A_NOT_B, __GETNEWARGS_EX__, "tuple of length 2", length);
            }

            SequenceStorage sequenceStorage = getSequenceStorageNode.execute(newargs);
            Object args = getItemNode.execute(frame, sequenceStorage, 0);
            Object kwargs = getItemNode.execute(frame, sequenceStorage, 1);

            if (!isTuple(args, argsProfile)) {
                throw raise(TypeError, MUST_BE_TYPE_A_NOT_TYPE_B, "first item of the tuple returned by __getnewargs_ex__", "tuple", args);
            }
            if (!isDict(kwargs, kwargsProfile)) {
                throw raise(TypeError, MUST_BE_TYPE_A_NOT_TYPE_B, "second item of the tuple returned by __getnewargs_ex__", "dict", kwargs);
            }

            return Pair.create(args, kwargs);
        }

        @Specialization(guards = "!isNoValue(getNewArgsAttr)", limit = "getCallSiteInlineCacheMaxDepth()")
        Pair<Object, Object> doNewArgs(VirtualFrame frame, @SuppressWarnings("unused") PNone getNewArgsExAttr, Object getNewArgsAttr,
                        @Cached IsBuiltinClassProfile argsProfile,
                        @CachedLibrary(value = "getNewArgsAttr") PythonObjectLibrary pol) {
            Object args = pol.callObject(getNewArgsAttr, frame);
            if (!isTuple(args, argsProfile)) {
                throw raise(TypeError, SHOULD_RETURN_TYPE_A_NOT_TYPE_B, __GETNEWARGS__, "tuple", args);
            }
            return Pair.create(args, PNone.NONE);
        }

        @Specialization
        Pair<Object, Object> doHasNeither(@SuppressWarnings("unused") PNone getNewArgsExAttr, @SuppressWarnings("unused") PNone getNewArgsAttr) {
            return Pair.create(PNone.NONE, PNone.NONE);
        }
    }

    @ImportStatic({PythonOptions.class, PGuards.class})
    abstract static class GetSlotNamesDispatchNode extends Node {
        public abstract Object execute(VirtualFrame frame, Object cls, Object copyReg);

        @Specialization(limit = "getCallSiteInlineCacheMaxDepth()")
        Object dispatch(VirtualFrame frame, Object cls, Object copyReg,
                        @Cached GetSlotNamesNode getSlotNamesNode,
                        @CachedLibrary(value = "cls") PythonObjectLibrary pol) {
            Object slotNamesAttr = pol.lookupAttribute(cls, frame, __SLOTNAMES__);
            return getSlotNamesNode.execute(frame, cls, copyReg, slotNamesAttr);
        }
    }

    abstract static class GetSlotNamesNode extends BaseReduceNode {
        public abstract Object execute(VirtualFrame frame, Object cls, Object copyReg, Object slotNamesAttr);

        @Specialization(guards = "!isNoValue(slotNamesAttr)")
        Object getSlotNames(Object cls, @SuppressWarnings("unused") Object copyReg, Object slotNamesAttr,
                        @Cached IsBuiltinClassProfile slotnamesProfile) {
            Object names = slotNamesAttr;
            if (!PGuards.isNone(names) && !isList(names, slotnamesProfile)) {
                throw raise(TypeError, SLOTNAMES_SHOULD_BE_A_NOT_B, cls, "list or None", names);
            }
            return names;
        }

        @Specialization(limit = "getCallSiteInlineCacheMaxDepth()")
        Object getCopyRegSlotNames(VirtualFrame frame, Object cls, Object copyReg, @SuppressWarnings("unused") PNone slotNamesAttr,
                        @Cached IsBuiltinClassProfile slotnamesProfile,
                        @CachedLibrary(value = "copyReg") PythonObjectLibrary pol) {
            Object names = pol.lookupAndCallRegularMethod(copyReg, frame, "_slotnames", cls);
            if (!PGuards.isNone(names) && !isList(names, slotnamesProfile)) {
                throw raise(TypeError, COPYREG_SLOTNAMES);
            }
            return names;
        }
    }

    @ImportStatic({PythonOptions.class, PGuards.class})
    abstract static class GetStateDispatchNode extends Node {
        public abstract Object execute(VirtualFrame frame, Object obj, boolean required, Object copyReg);

        @Specialization(limit = "getCallSiteInlineCacheMaxDepth()")
        Object dispatch(VirtualFrame frame, Object obj, boolean required, Object copyReg,
                        @Cached GetStateNode getStateNode,
                        @CachedLibrary(value = "obj") PythonObjectLibrary pol) {
            Object getStateAttr = pol.lookupAttribute(obj, frame, __GETSTATE__);
            return getStateNode.execute(frame, obj, required, copyReg, getStateAttr);
        }
    }

    abstract static class GetStateNode extends BaseReduceNode {
        public abstract Object execute(VirtualFrame frame, Object obj, boolean required, Object copyReg, Object getStateAttr);

        @Specialization(guards = "!isNoValue(getStateAttr)", limit = "getCallSiteInlineCacheMaxDepth()")
        Object getState(VirtualFrame frame, @SuppressWarnings("unused") Object obj, @SuppressWarnings("unused") boolean required, @SuppressWarnings("unused") Object copyReg, Object getStateAttr,
                        @CachedLibrary(value = "getStateAttr") PythonObjectLibrary pol) {
            return pol.callObject(getStateAttr, frame);
        }

        @Specialization
        Object getStateFromSlots(VirtualFrame frame, Object obj, boolean required, Object copyReg, @SuppressWarnings("unused") PNone getStateAttr,
                        @Cached SequenceNodes.GetSequenceStorageNode getSequenceStorageNode,
                        @Cached SequenceStorageNodes.ToArrayNode toArrayNode,
                        @Cached TypeNodes.GetItemsizeNode getItemsizeNode,
                        @Cached CastToJavaStringNode toJavaStringNode,
                        @Cached GetSlotNamesDispatchNode getSlotNamesNode,
                        @CachedLibrary(limit = "getCallSiteInlineCacheMaxDepth()") PythonObjectLibrary pol,
                        @CachedLibrary(limit = "1") HashingStorageLibrary hlib) {
            Object state;
            Object type = pol.getLazyPythonClass(obj);
            if (required && getItemsizeNode.execute(type) != 0) {
                throw raise(TypeError, CANNOT_PICKLE_OBJECT_TYPE, obj);
            }

            state = pol.getDict(obj);
            if (state == null) {
                state = PNone.NONE;
            }

            // we skip the assert that type is a type since we are certain of that in this case
            Object slotnames = getSlotNamesNode.execute(frame, type, copyReg);
            // TODO check basicsize typeobject.c:4213

            if (!PGuards.isNone(slotnames) && pol.length(slotnames) > 0) {
                SequenceStorage sequenceStorage = getSequenceStorageNode.execute(slotnames);
                Object[] names = toArrayNode.execute(sequenceStorage);

                PDict slots = factory().createDict();
                boolean haveSlots = false;
                for (Object o : names) {
                    try {
                        String name = toJavaStringNode.execute(o);
                        Object value = pol.lookupAttribute(obj, frame, name);
                        if (!PGuards.isNoValue(value)) {
                            hlib.setItem(slots.getDictStorage(), name, value);
                            haveSlots = true;
                        }
                    } catch (CannotCastException cce) {
                        throw raise(TypeError, ATTR_NAME_MUST_BE_STRING, o);
                    }
                }
                if (haveSlots) {
                    state = factory().createTuple(new Object[]{state, slots});
                }
            }

            return state;
        }
    }

    abstract static class CommonReduceNode extends BaseReduceNode {
        public abstract Object execute(VirtualFrame frame, Object obj, int proto);

        @Specialization(guards = "proto >= 2")
        public Object reduceNewObj(VirtualFrame frame, Object obj, @SuppressWarnings("unused") int proto,
                        @Cached ConditionProfile hasArgsProfile,
                        @Cached IsBuiltinClassProfile objProfile,
                        @Cached GetNewArgsDispatchNode getNewArgsNode,
                        @Cached GetStateDispatchNode getStateNode,
                        @Cached SequenceNodes.GetSequenceStorageNode getSequenceStorageNode,
                        @Cached SequenceStorageNodes.ToArrayNode toArrayNode,
                        @CachedLibrary(limit = "getCallSiteInlineCacheMaxDepth()") PythonObjectLibrary pol) {
            Object cls = pol.lookupAttribute(obj, frame, __CLASS__);
            if (pol.lookupAttribute(cls, frame, __NEW__) == PNone.NO_VALUE) {
                throw raise(TypeError, CANNOT_PICKLE_OBJECT_TYPE, obj);
            }

            Pair<Object, Object> rv = getNewArgsNode.execute(frame, obj);
            Object args = rv.getLeft();
            Object kwargs = rv.getRight();
            Object newobj, newargs;
            Object copyReg = importCopyReg(frame);

            boolean hasargs = args != PNone.NONE;

            if (kwargs == PNone.NONE || pol.length(kwargs) == 0) {
                newobj = pol.lookupAttribute(copyReg, frame, __NEWOBJ__);
                Object[] newargsVals;
                if (hasArgsProfile.profile(hasargs)) {
                    SequenceStorage sequenceStorage = getSequenceStorageNode.execute(args);
                    Object[] vals = toArrayNode.execute(sequenceStorage);
                    newargsVals = new Object[vals.length + 1];
                    newargsVals[0] = cls;
                    System.arraycopy(vals, 0, newargsVals, 1, vals.length);
                } else {
                    newargsVals = new Object[]{cls};
                }
                newargs = factory().createTuple(newargsVals);
            } else if (hasArgsProfile.profile(hasargs)) {
                newobj = pol.lookupAttribute(copyReg, frame, __NEWOBJ_EX__);
                newargs = factory().createTuple(new Object[]{cls, args, kwargs});
            } else {
                throw raise(SystemError, BAD_ARG_TO_INTERNAL_FUNC);
            }

            boolean objIsDict = isDict(obj, objProfile);
            boolean objIsList = isList(obj, objProfile);
            boolean required = !hasargs && !objIsDict && !objIsList;

            Object state = getStateNode.execute(frame, obj, required, copyReg);
            Object listitems = objIsList ? pol.getIterator(obj) : PNone.NONE;
            Object dictitems = objIsDict ? pol.getIterator(pol.lookupAndCallRegularMethod(obj, frame, ITEMS)) : PNone.NONE;

            return factory().createTuple(new Object[]{newobj, newargs, state, listitems, dictitems});
        }

        @Specialization(guards = "proto < 2")
        public Object reduceCopyReg(VirtualFrame frame, Object obj, int proto,
                        @CachedLibrary(limit = "1") PythonObjectLibrary pol) {
            Object copyReg = importCopyReg(frame);
            return pol.lookupAndCallRegularMethod(copyReg, frame, "_reduce_ex", obj, proto);
        }
    }
}
