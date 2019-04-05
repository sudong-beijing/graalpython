package com.oracle.graal.python.nodes.util;

import com.oracle.graal.python.PythonLanguage;
import com.oracle.graal.python.builtins.objects.function.PArguments;
import com.oracle.graal.python.nodes.PRootNode;
import com.oracle.graal.python.nodes.frame.FrameSlotIDs;
import com.oracle.graal.python.nodes.util.ExceptionStateNodesFactory.GetCaughtExceptionNodeGen;
import com.oracle.graal.python.nodes.util.ExceptionStateNodesFactory.ReadExceptionStateFromFrameNodeGen;
import com.oracle.graal.python.nodes.util.ExceptionStateNodesFactory.RestoreExceptionStateNodeGen;
import com.oracle.graal.python.runtime.PythonContext;
import com.oracle.graal.python.runtime.exception.PException;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.CompilerDirectives.ValueType;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.FrameInstance.FrameAccess;
import com.oracle.truffle.api.frame.FrameInstanceVisitor;
import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.FrameUtil;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.profiles.ConditionProfile;

public abstract class ExceptionStateNodes {

    public static final class SetCaughtExceptionNode extends Node {

        @CompilationFinal private FrameSlot excSlot;
        @CompilationFinal private ContextReference<PythonContext> contextRef;

        public void execute(VirtualFrame frame, PException e) {
            RootNode rootNode = getRootNode();
            if (rootNode != null && !shouldStoreException(rootNode)) {
                doFrame(frame, e, rootNode);
                doContext(PException.LAZY_FETCH_EXCEPTION);
            } else {
                doContext(e);
            }
        }

        private void doFrame(VirtualFrame frame, PException e, RootNode rootNode) {
            if (excSlot == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                excSlot = findFameSlot(frame, rootNode);
            }
            frame.setObject(excSlot, e);
        }

        protected static FrameSlot findFameSlot(VirtualFrame frame, RootNode rootNode) {
            FrameDescriptor frameDescriptor = rootNode.getFrameDescriptor();

            FrameSlot frameSlot = frameDescriptor.findOrAddFrameSlot(FrameSlotIDs.CAUGHT_EXCEPTION, FrameSlotKind.Object);
            if (frame.getFrameDescriptor().getFrameSlotKind(frameSlot) != FrameSlotKind.Object) {
                frame.getFrameDescriptor().setFrameSlotKind(frameSlot, FrameSlotKind.Object);
            }
            return frameSlot;
        }

        private void doContext(PException e) {
            if (contextRef == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                contextRef = PythonLanguage.getContextRef();
            }
            contextRef.get().setCaughtException(e);
        }

        private static boolean shouldStoreException(RootNode rootNode) {
            // TODO
// if (rootNode instanceof PRootNode) {
// return ((PRootNode) rootNode).storeExceptionState();
// }
// return true;
            return false;
        }

        public static SetCaughtExceptionNode create() {
            return new SetCaughtExceptionNode();
        }
    }

    public abstract static class ReadExceptionStateFromFrameNode extends Node {

        public abstract PException execute(Frame frame);

        @Specialization(guards = "frame.getFrameDescriptor() == fd")
        PException doFrameCached(Frame frame,
                        @Cached("frame.getFrameDescriptor()") @SuppressWarnings("unused") FrameDescriptor fd,
                        @Cached("findSlot(fd)") FrameSlot excSlot) {
            if (excSlot != null) {
                return (PException) FrameUtil.getObjectSafe(frame, excSlot);
            }
            return null;
        }

        @Specialization
        PException doFrameGeneric(Frame frame) {
            return doFrameCached(frame, null, findSlot(frame.getFrameDescriptor()));
        }

        @TruffleBoundary
        protected static FrameSlot findSlot(FrameDescriptor frameDescriptor) {
            FrameSlot excSlot = frameDescriptor.findFrameSlot(FrameSlotIDs.CAUGHT_EXCEPTION);
            assert excSlot == null || frameDescriptor.getFrameSlotKind(excSlot) == FrameSlotKind.Object;
            return excSlot;
        }

    }

    public static final class ReadExceptionStateFromArgsNode extends Node {

        @Child ReadExceptionStateFromFrameNode readFrameNode;

        public PException execute(Object callerFrameOrExceptionArg) {
            if (callerFrameOrExceptionArg == null || callerFrameOrExceptionArg instanceof PException) {
                return (PException) callerFrameOrExceptionArg;
            } else if (callerFrameOrExceptionArg instanceof Frame) {
                if (readFrameNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    readFrameNode = insert(ReadExceptionStateFromFrameNodeGen.create());
                }
                return readFrameNode.execute((Frame) callerFrameOrExceptionArg);
            }
            CompilerDirectives.transferToInterpreter();
            throw new IllegalStateException();
        }

        public static ReadExceptionStateFromArgsNode create() {
            return new ReadExceptionStateFromArgsNode();
        }
    }

    public abstract static class GetCaughtExceptionNode extends Node {

        public abstract ExceptionState execute(VirtualFrame frame);

        public final PException executeException(VirtualFrame frame) {
            return execute(frame).exc;
        }

        @Specialization
        ExceptionState doFrameAndArgs(@SuppressWarnings("unused") VirtualFrame frame,
                        @Cached("findSlot(frame)") FrameSlot excSlot,
                        @Cached("createBinaryProfile()") ConditionProfile profile,
                        @Cached ReadExceptionStateFromArgsNode readFromArgsNode) {

            PException e = (PException) FrameUtil.getObjectSafe(frame, excSlot);
            if (profile.profile(e != null)) {
                return new ExceptionState(e, ExceptionState.SOURCE_FRAME);
            }

            e = readFromArgsNode.execute(PArguments.getCallerFrameOrException(frame));
            if (e == null) {
                // The very-slow path: This is the first time we want to fetch the exception state
                // from the context. The caller didn't know that it is necessary to provide the
                // exception in the context. So, we do a full stack walk until the first frame
                // having the exception state in the special slot. And we set the appropriate flag
                // on the root node such that the next time, we will find the exception state in the
                // context immediately.
                CompilerDirectives.transferToInterpreter();

                // TODO How to prevent this case to be repeated?
                e = fullStackWalk();
            }
            return new ExceptionState(ensure(e), ExceptionState.SOURCE_CALLER);
        }

        @TruffleBoundary
        private static PException fullStackWalk() {

            return Truffle.getRuntime().iterateFrames(new FrameInstanceVisitor<PException>() {
                public PException visitFrame(FrameInstance frameInstance) {
                    RootCallTarget target = (RootCallTarget) frameInstance.getCallTarget();
                    RootNode rootNode = target.getRootNode();
                    if (rootNode instanceof PRootNode) {
                        PRootNode pRootNode = (PRootNode) rootNode;
                        pRootNode.setStoreExceptionState();
                        FrameDescriptor fd = rootNode.getFrameDescriptor();
                        FrameSlot excSlot = fd.findFrameSlot(FrameSlotIDs.CAUGHT_EXCEPTION);
                        if (excSlot != null) {
                            Frame frame = frameInstance.getFrame(FrameAccess.READ_ONLY);
                            try {
                                Object object = frame.getObject(excSlot);
                                if (object instanceof PException) {
                                    return (PException) object;
                                }
                            } catch (FrameSlotTypeException e) {
                                // fall through
                            }
                        }
                    }
                    return null;
                }
            });

        }

        private static PException ensure(PException e) {
            return e != PException.NO_EXCEPTION ? e : null;
        }

        protected FrameSlot findSlot(VirtualFrame frame) {
            RootNode rootNode = getRootNode();
            if (rootNode != null) {
                return SetCaughtExceptionNode.findFameSlot(frame, rootNode);
            }
            return null;
        }

        public static GetCaughtExceptionNode create() {
            return GetCaughtExceptionNodeGen.create();
        }

    }

    public static final class SaveExceptionStateNode extends Node {

        @CompilationFinal private FrameSlot excSlot;

        public ExceptionState execute(VirtualFrame frame) {

            if (excSlot == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                excSlot = findSlot(frame);
            }
            PException e = (PException) FrameUtil.getObjectSafe(frame, excSlot);
            return new ExceptionState(e, ExceptionState.SOURCE_FRAME);
        }

        protected FrameSlot findSlot(VirtualFrame frame) {
            RootNode rootNode = getRootNode();
            if (rootNode != null) {
                return SetCaughtExceptionNode.findFameSlot(frame, rootNode);
            }
            return null;
        }

        public static SaveExceptionStateNode create() {
            return new SaveExceptionStateNode();
        }

    }

    public abstract static class RestoreExceptionStateNode extends Node {

        public abstract void execute(VirtualFrame frame, ExceptionState state);

        @SuppressWarnings("unused")
        @Specialization(guards = "state == null")
        void doNothing(VirtualFrame frame, ExceptionState state) {
        }

        @Specialization(guards = "fromFrame(e)")
        void doFrame(VirtualFrame frame, ExceptionState e,
                        @Cached("findFrameSlot(frame)") FrameSlot excSlot) {
            frame.setObject(excSlot, e.exc);
        }

        @Specialization(guards = "fromContext(e)", replaces = "doFrame")
        void doContext(@SuppressWarnings("unused") VirtualFrame frame, ExceptionState e,
                        @Shared("context") @CachedContext(PythonLanguage.class) PythonContext context) {
            context.setCaughtException(e.exc);
        }

        @Specialization(replaces = "doContext")
        void doGeneric(VirtualFrame frame, ExceptionState e,
                        @Cached("findFrameSlot(frame)") @SuppressWarnings("unused") FrameSlot excSlot,
                        @Shared("context") @CachedContext(PythonLanguage.class) PythonContext context) {
            if (e == null) {
                return;
            }
            if (fromFrame(e)) {
                if (excSlot == null) {
                    CompilerDirectives.transferToInterpreter();
                    throw new IllegalStateException();
                }
                doFrame(frame, e, excSlot);
            } else if (fromContext(e)) {
                doContext(frame, e, context);
            }
        }

        protected FrameSlot findFrameSlot(VirtualFrame frame) {
            RootNode rootNode = getRootNode();
            if (rootNode != null) {
                return SetCaughtExceptionNode.findFameSlot(frame, rootNode);
            }
            return null;
        }

        protected static boolean fromFrame(ExceptionState state) {
            return state.source == ExceptionState.SOURCE_FRAME;
        }

        protected static boolean fromContext(ExceptionState state) {
            return state.source == ExceptionState.SOURCE_CONTEXT;
        }

        public static RestoreExceptionStateNode create() {
            return RestoreExceptionStateNodeGen.create();
        }
    }

    @ValueType
    public static final class ExceptionState {
        public static final int SOURCE_FRAME = 0;
        public static final int SOURCE_CONTEXT = 1;
        public static final int SOURCE_GENERATOR = 2;
        public static final int SOURCE_CALLER = 3;

        public final PException exc;
        public final int source;

        public ExceptionState(PException exc, int source) {
            this.exc = exc;
            this.source = source;
        }
    }
}
