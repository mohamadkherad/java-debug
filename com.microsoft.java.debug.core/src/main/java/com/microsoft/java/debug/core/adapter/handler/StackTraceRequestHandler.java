/*******************************************************************************
* Copyright (c) 2017 Microsoft Corporation and others.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Eclipse Public License v1.0
* which accompanies this distribution, and is available at
* http://www.eclipse.org/legal/epl-v10.html
*
* Contributors:
*     Microsoft Corporation - initial API and implementation
*******************************************************************************/

package com.microsoft.java.debug.core.adapter.handler;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.microsoft.java.debug.core.DebugUtility;
import com.microsoft.java.debug.core.adapter.AdapterUtils;
import com.microsoft.java.debug.core.adapter.IDebugAdapterContext;
import com.microsoft.java.debug.core.adapter.IDebugRequestHandler;
import com.microsoft.java.debug.core.adapter.ISourceLookUpProvider;
import com.microsoft.java.debug.core.adapter.Messages.Response;
import com.microsoft.java.debug.core.adapter.Requests.Arguments;
import com.microsoft.java.debug.core.adapter.Requests.Command;
import com.microsoft.java.debug.core.adapter.Requests.StackTraceArguments;
import com.microsoft.java.debug.core.adapter.Responses;
import com.microsoft.java.debug.core.adapter.Types;
import com.microsoft.java.debug.core.adapter.variables.JdiObjectProxy;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;

public class StackTraceRequestHandler implements IDebugRequestHandler {

    @Override
    public List<Command> getTargetCommands() {
        return Arrays.asList(Command.STACKTRACE);
    }

    @Override
    public void handle(Command command, Arguments arguments, Response response, IDebugAdapterContext context) {
        StackTraceArguments stacktraceArgs = (StackTraceArguments) arguments;
        List<Types.StackFrame> result = new ArrayList<>();
        if (stacktraceArgs.startFrame < 0 || stacktraceArgs.levels < 0) {
            response.body = new Responses.StackTraceResponseBody(result, 0);
            return;
        }
        ThreadReference thread = DebugUtility.getThread(context.getDebugSession(), stacktraceArgs.threadId);
        int totalFrames = 0;
        if (thread != null) {
            try {
                totalFrames = thread.frameCount();
                if (totalFrames <= stacktraceArgs.startFrame) {
                    response.body = new Responses.StackTraceResponseBody(result, totalFrames);
                    return;
                }
                List<StackFrame> stackFrames = stacktraceArgs.levels == 0
                        ? thread.frames(stacktraceArgs.startFrame, totalFrames - stacktraceArgs.startFrame)
                        : thread.frames(stacktraceArgs.startFrame,
                        Math.min(totalFrames - stacktraceArgs.startFrame, stacktraceArgs.levels));
                for (int i = 0; i < stackFrames.size(); i++) {
                    StackFrame stackFrame = stackFrames.get(i);
                    int frameId = context.getRecyclableIdPool().addObject(stackFrame.thread().uniqueID(),
                            new JdiObjectProxy<>(stackFrame));
                    Types.StackFrame clientStackFrame = convertDebuggerStackFrameToClient(stackFrame, frameId, context);
                    result.add(clientStackFrame);
                }
            } catch (IncompatibleThreadStateException | IndexOutOfBoundsException | URISyntaxException
                    | AbsentInformationException | ObjectCollectedException e) {
                // when error happens, the possible reason is:
                // 1. the vscode has wrong parameter/wrong uri
                // 2. the thread actually terminates

                // TODO: should record a error log here.
            }
        }
        response.body = new Responses.StackTraceResponseBody(result, totalFrames);
    }

    private Types.StackFrame convertDebuggerStackFrameToClient(StackFrame stackFrame, int frameId, IDebugAdapterContext context)
            throws URISyntaxException, AbsentInformationException {
        Location location = stackFrame.location();
        Method method = location.method();
        Types.Source clientSource = this.convertDebuggerSourceToClient(location, context);
        String methodName = method.name();
        int lineNumber = AdapterUtils.convertLineNumber(location.lineNumber(), context.isDebuggerLinesStartAt1(), context.isClientLinesStartAt1());
        // Line number returns -1 if the information is not available; specifically, always returns -1 for native methods.
        if (lineNumber < 0) {
            if (method.isNative()) {
                // For native method, display a tip text "native method" in the Call Stack View.
                methodName += "[native method]";
            } else {
                // For other unavailable method, such as lambda expression's built-in methods run/accept/apply,
                // display "Unknown Source" in the Call Stack View.
                clientSource = null;
            }
        }
        return new Types.StackFrame(frameId, methodName, clientSource, lineNumber, 0);
    }

    private Types.Source convertDebuggerSourceToClient(Location location, IDebugAdapterContext context) throws URISyntaxException {
        final String fullyQualifiedName = location.declaringType().name();
        String sourceName = "";
        String relativeSourcePath = "";
        try {
            // When the .class file doesn't contain source information in meta data,
            // invoking Location#sourceName() would throw AbsentInformationException.
            sourceName = location.sourceName();
            relativeSourcePath = location.sourcePath();
        } catch (AbsentInformationException e) {
            String enclosingType = AdapterUtils.parseEnclosingType(fullyQualifiedName);
            sourceName = enclosingType.substring(enclosingType.lastIndexOf('.') + 1) + ".java";
            relativeSourcePath = enclosingType.replace('.', '/') + ".java";
        }

        final String finalRelativeSourcePath = relativeSourcePath;
        // use a lru cache for better performance
        String uri = context.getSourceLookupCache().computeIfAbsent(fullyQualifiedName, key -> {
            String fromProvider = context.getProvider(ISourceLookUpProvider.class).getSourceFileURI(key, finalRelativeSourcePath);
            // avoid return null which will cause the compute function executed again
            return StringUtils.isBlank(fromProvider) ? "" : fromProvider;
        });

        if (!StringUtils.isBlank(uri)) {
            // The Source.path could be a file system path or uri string.
            if (uri.startsWith("file:")) {
                String clientPath = AdapterUtils.convertPath(uri, context.isDebuggerPathsAreUri(), context.isClientPathsAreUri());
                return new Types.Source(sourceName, clientPath, 0);
            } else {
                // If the debugger returns uri in the Source.path for the StackTrace response, VSCode client will try to find a TextDocumentContentProvider
                // to render the contents.
                // Language Support for Java by Red Hat extension has already registered a jdt TextDocumentContentProvider to parse the jdt-based uri.
                // The jdt uri looks like 'jdt://contents/rt.jar/java.io/PrintStream.class?=1.helloworld/%5C/usr%5C/lib%5C/jvm%5C/java-8-oracle%5C/jre%5C/
                // lib%5C/rt.jar%3Cjava.io(PrintStream.class'.
                return new Types.Source(sourceName, uri, 0);
            }
        } else {
            // If the source lookup engine cannot find the source file, then lookup it in the source directories specified by user.
            String absoluteSourcepath = AdapterUtils.sourceLookup(context.getSourcePaths(), relativeSourcePath);
            if (absoluteSourcepath != null) {
                return new Types.Source(sourceName, absoluteSourcepath, 0);
            } else {
                return null;
            }
        }
    }
}