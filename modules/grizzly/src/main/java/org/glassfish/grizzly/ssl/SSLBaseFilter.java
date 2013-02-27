/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012-2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.grizzly.ssl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Filter;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.Context;
import org.glassfish.grizzly.Event;
import org.glassfish.grizzly.EventProcessingHandler;
import org.glassfish.grizzly.FileTransfer;
import org.glassfish.grizzly.Grizzly;
import org.glassfish.grizzly.IOEvent;
import org.glassfish.grizzly.ProcessorExecutor;
import org.glassfish.grizzly.ReadResult;
import org.glassfish.grizzly.WritableMessage;
import org.glassfish.grizzly.asyncqueue.LifeCycleHandler;
import org.glassfish.grizzly.filterchain.BaseFilter;
import org.glassfish.grizzly.filterchain.FilterChain;
import org.glassfish.grizzly.filterchain.FilterChainBuilder;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.filterchain.NextAction;
import org.glassfish.grizzly.filterchain.TransportFilter;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.CompositeBuffer;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.ssl.SSLConnectionContext.Allocator;

import static org.glassfish.grizzly.ssl.SSLUtils.*;

/**
 * SSL {@link Filter} to operate with SSL encrypted data.
 *
 * @author Alexey Stashok
 */
public class SSLBaseFilter extends BaseFilter {
    private static final Logger LOGGER = Grizzly.logger(SSLBaseFilter.class);

    private static final Allocator MM_ALLOCATOR = new Allocator() {
        @Override
        @SuppressWarnings("unchecked")
        public Buffer grow(final SSLConnectionContext sslCtx,
            final Buffer oldBuffer, final int newSize) {
            final MemoryManager mm = sslCtx.getConnection()
                    .getTransport().getMemoryManager();
            
            if (oldBuffer == null) {
                return mm.allocate(newSize);
            } else {
                return mm.reallocate(oldBuffer,
                        newSize);
            }
        }
    };
    
    private static final SSLConnectionContext.Allocator OUTPUT_BUFFER_ALLOCATOR =
            new SSLConnectionContext.Allocator() {
        @Override
        public Buffer grow(final SSLConnectionContext sslCtx,
            final Buffer oldBuffer, final int newSize) {
            
            return allocateOutputBuffer(newSize);
        }
    };
    
    private final SSLEngineConfigurator serverSSLEngineConfigurator;
    private final boolean renegotiateOnClientAuthWant;

    protected final Set<HandshakeListener> handshakeListeners =
            Collections.newSetFromMap(new ConcurrentHashMap<HandshakeListener, Boolean>());
    
    private long handshakeTimeoutMillis = -1;
    
    // ------------------------------------------------------------ Constructors


    public SSLBaseFilter() {
        this(null);
    }

    /**
     * Build <tt>SSLFilter</tt> with the given {@link SSLEngineConfigurator}.
     *
     * @param serverSSLEngineConfigurator SSLEngine configurator for server side connections
     */
    public SSLBaseFilter(SSLEngineConfigurator serverSSLEngineConfigurator) {
        this(serverSSLEngineConfigurator, true);
    }


    /**
     * Build <tt>SSLFilter</tt> with the given {@link SSLEngineConfigurator}.
     *
     * @param serverSSLEngineConfigurator SSLEngine configurator for server side connections
     */
    public SSLBaseFilter(SSLEngineConfigurator serverSSLEngineConfigurator,
                     boolean renegotiateOnClientAuthWant) {
        
        this.renegotiateOnClientAuthWant = renegotiateOnClientAuthWant;
        if (serverSSLEngineConfigurator == null) {
            this.serverSSLEngineConfigurator = new SSLEngineConfigurator(
                    SSLContextConfigurator.DEFAULT_CONFIG.createSSLContext(),
                    false, false, false);
        } else {
            this.serverSSLEngineConfigurator = serverSSLEngineConfigurator;
        }
    }

    public void addHandshakeListener(final HandshakeListener listener) {
        handshakeListeners.add(listener);
    }
    
    public void removeHandshakeListener(final HandshakeListener listener) {
        handshakeListeners.remove(listener);
    }

    /**
     * Returns the handshake timeout, <code>-1</code> if blocking handshake mode
     * is disabled (default).
     */
    public long getHandshakeTimeout(final TimeUnit timeUnit) {
        if (handshakeTimeoutMillis < 0) {
            return -1;
        }
        
        return timeUnit.convert(handshakeTimeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Sets the handshake timeout.
     * @param handshakeTimeout timeout value, or <code>-1</code> means for
     * non-blocking handshake mode.
     */
    public void setHandshakeTimeout(final long handshakeTimeout,
            final TimeUnit timeUnit) {
        if (handshakeTimeout < 0) {
            handshakeTimeoutMillis = -1;
        } else {
            this.handshakeTimeoutMillis =
                    TimeUnit.MILLISECONDS.convert(handshakeTimeout, timeUnit);
        }
    }

    
    public TransportFilter createOptimizedTransportFilter(final TransportFilter childFilter) {
        return new SSLTransportFilterWrapper(childFilter);
    }

    @Override
    public void onBeforeFilterChainConstructed(final FilterChainBuilder builder) {
        final boolean isSsl = builder.indexOfType(SSLBaseFilter.class) >= 0;
        final int sslTransportFilterIdx = builder.indexOfType(SSLTransportFilterWrapper.class);
        
        if (isSsl) {
            if (sslTransportFilterIdx == -1) {
                final int transportFilterIdx =
                        builder.indexOfType(TransportFilter.class);
                if (transportFilterIdx >= 0) {
                    builder.set(transportFilterIdx,
                            createOptimizedTransportFilter(
                            (TransportFilter) builder.get(transportFilterIdx)));
                }
            }
        } else { // SSLBaseFilter has been removed
            if (sslTransportFilterIdx >= 0) {
                SSLTransportFilterWrapper wrapper =
                        (SSLTransportFilterWrapper) builder.get(sslTransportFilterIdx);
                builder.set(sslTransportFilterIdx, wrapper.transportFilter);
            }
        }
    }

    
    // ----------------------------------------------------- Methods from Filter


    @Override
    public NextAction handleEvent(FilterChainContext ctx, Event event) throws IOException {
        if (event.type() == CertificateEvent.TYPE) {
            final CertificateEvent ce = (CertificateEvent) event;
            ce.certs = getPeerCertificateChain(getSslConnectionContext(ctx.getConnection()),
                                               ctx,
                                               ce.needClientAuth);
            return ctx.getStopAction();
        }
        return ctx.getInvokeAction();
    }

    @Override
    public NextAction handleRead(final FilterChainContext ctx)
    throws IOException {
        final Connection connection = ctx.getConnection();
        final SSLConnectionContext sslCtx = getSslConnectionContext(connection);
        SSLEngine sslEngine = sslCtx.getSslEngine();
        
        if (sslEngine != null && !isHandshaking(sslEngine)) {
            return unwrapAll(ctx, sslCtx);
        } else {
            if (sslEngine == null) {
                sslEngine = serverSSLEngineConfigurator.createSSLEngine();
                sslEngine.beginHandshake();
                sslCtx.configure(sslEngine);
                notifyHandshakeStart(connection);
            }

            final Buffer buffer;
            if (handshakeTimeoutMillis >= 0) {
                buffer = doHandshakeSync(sslCtx, ctx, (Buffer) ctx.getMessage(),
                        handshakeTimeoutMillis);
            } else {
                buffer = makeInputRemainder(sslCtx, ctx,
                        doHandshakeStep(sslCtx, ctx, (Buffer) ctx.getMessage()));
            }
        
            final boolean hasRemaining = buffer != null && buffer.hasRemaining();
            
            final boolean isHandshaking = isHandshaking(sslEngine);
            if (!isHandshaking) {
                notifyHandshakeComplete(connection, sslEngine);
                final FilterChain connectionFilterChain = sslCtx.getNewConnectionFilterChain();
                sslCtx.setNewConnectionFilterChain(null);
                if (connectionFilterChain != null) {
                    connection.setFilterChain(connectionFilterChain);
                    
                    if (hasRemaining) {
                        NextAction suspendAction = ctx.getSuspendAction();
                        ctx.setMessage(buffer);
                        ctx.suspend();
                        final FilterChainContext newContext =
                                obtainProtocolChainContext(ctx, connectionFilterChain);
                        ProcessorExecutor.execute(newContext.getInternalContext());
                        return suspendAction;
                    } else {
                        return ctx.getStopAction();
                    }
                }
                
                if (hasRemaining) {
                    ctx.setMessage(buffer);
                    return unwrapAll(ctx, sslCtx);
                }
            }

            return ctx.getStopAction(buffer);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public NextAction handleWrite(final FilterChainContext ctx) throws IOException {
        if (ctx.getMessage() instanceof FileTransfer) {
            throw new IllegalStateException("TLS operations not supported with SendFile messages");
        }

        final Connection connection = ctx.getConnection();
        
        synchronized(connection) {
            final Buffer output =
                    wrapAll(ctx, getSslConnectionContext(connection));

            final FilterChainContext.TransportContext transportContext =
                    ctx.getTransportContext();

            ctx.write(null, output,
                    transportContext.getCompletionHandler(),
                    new OnWriteCopyCloner(transportContext.getLifeCycleHandler()),
                    transportContext.isBlocking());

            return ctx.getStopAction();
        }
    }

    // ------------------------------------------------------- Protected Methods

    protected NextAction unwrapAll(final FilterChainContext ctx,
            final SSLConnectionContext sslCtx) throws SSLException {
        Buffer input = ctx.getMessage();
        
        Buffer output = null;
        
        boolean isClosed = false;
        
        _outter:
        do {
            final int len = getSSLPacketSize(input);
            
            if (len == -1 || input.remaining() < len) {
                break;
            }

            final SSLConnectionContext.SslResult result =
                    sslCtx.unwrap(input, output, MM_ALLOCATOR);
            
            if (isHandshaking(sslCtx.getSslEngine())) {
                input = rehandshake(ctx, sslCtx);
                if (input == null) {
                    break;
                }
            }
            
            output = result.getOutput();

            if (result.isError()) {
                output.dispose();
                throw result.getError();
            }
            
            switch (result.getSslEngineResult().getStatus()) {
                case OK:
                    if (input.hasRemaining()) {
                        break;
                    }

                    break _outter;
                case CLOSED:
                    isClosed = true;
                    break _outter;
                default:
                    // Should not reach this point
                    throw new IllegalStateException("Unexpected status: " +
                            result.getSslEngineResult().getStatus());
            }
        } while (true);
        
        if (output != null) {
            output.trim();

            if (output.hasRemaining() || isClosed) {
                ctx.setMessage(output);
                return ctx.getInvokeAction(makeInputRemainder(sslCtx, ctx, input));
            }
        }

        return ctx.getStopAction(makeInputRemainder(sslCtx, ctx, input));
    }

    protected Buffer wrapAll(final FilterChainContext ctx,
            final SSLConnectionContext sslCtx) throws SSLException {
        
        final Buffer input = ctx.getMessage();
        
        final Buffer output = sslCtx.wrapAll(input, OUTPUT_BUFFER_ALLOCATOR);

        input.tryDispose();

        return output;
    }

//    protected Buffer doHandshakeStep1(final SSLConnectionContext sslCtx,
//                                     final FilterChainContext ctx,
//                                     Buffer inputBuffer)
//    throws IOException {
//
//        final SSLEngine sslEngine = sslCtx.getSslEngine();
//        final Connection connection = ctx.getConnection();
//
//        final boolean isLoggingFinest = LOGGER.isLoggable(Level.FINEST);
//        try {
//            HandshakeStatus handshakeStatus = sslEngine.getHandshakeStatus();
//
//            while (true) {
//
//                if (isLoggingFinest) {
//                    LOGGER.log(Level.FINEST, "Loop Engine: {0} handshakeStatus={1}",
//                            new Object[]{sslEngine, sslEngine.getHandshakeStatus()});
//                }
//
//                switch (handshakeStatus) {
//                    case NEED_UNWRAP: {
//
//                        if (isLoggingFinest) {
//                            LOGGER.log(Level.FINEST, "NEED_UNWRAP Engine: {0}", sslEngine);
//                        }
//
//                        if (inputBuffer == null || !inputBuffer.hasRemaining()) {
//                            return inputBuffer;
//                        }
//                        
//                        final int expectedLength = getSSLPacketSize(inputBuffer);
//                        if (expectedLength == -1
//                                || inputBuffer.remaining() < expectedLength) {
//                            return inputBuffer;
//                        }
//
//                        final SSLEngineResult sslEngineResult =
//                                handshakeUnwrap(connection, sslCtx, inputBuffer, null);
//
//                        inputBuffer.shrink();
//
//                        final SSLEngineResult.Status status = sslEngineResult.getStatus();
//
//                        if (status == SSLEngineResult.Status.BUFFER_UNDERFLOW ||
//                                status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
//                            throw new SSLException("SSL unwrap error: " + status);
//                        }
//
//                        handshakeStatus = sslEngine.getHandshakeStatus();
//                        break;
//                    }
//
//                    case NEED_WRAP: {
//                        if (isLoggingFinest) {
//                            LOGGER.log(Level.FINEST, "NEED_WRAP Engine: {0}", sslEngine);
//                        }
//
//                        inputBuffer = makeInputRemainder(sslCtx, ctx, inputBuffer);
//                        final Buffer buffer = handshakeWrap(connection, sslCtx, null);
//
//                        try {
//                            ctx.write(buffer);
//
//                            handshakeStatus = sslEngine.getHandshakeStatus();
//                        } catch (Exception e) {
//                            buffer.dispose();
//                            throw new IOException("Unexpected exception", e);
//                        }
//
//                        break;
//                    }
//
//                    case NEED_TASK: {
//                        if (isLoggingFinest) {
//                            LOGGER.log(Level.FINEST, "NEED_TASK Engine: {0}", sslEngine);
//                        }
//                        executeDelegatedTask(sslEngine);
//                        handshakeStatus = sslEngine.getHandshakeStatus();
//                        break;
//                    }
//
//                    case FINISHED:
//                    case NOT_HANDSHAKING: {
//                        return inputBuffer;
//                    }
//                }
//
//                if (handshakeStatus == HandshakeStatus.FINISHED) {
//                    return inputBuffer;
//                }
//            }
//        } catch (IOException ioe) {
//            notifyHandshakeFailed(connection, ioe);
//            throw ioe;
//        }
//    }

    protected Buffer doHandshakeSync(final SSLConnectionContext sslCtx,
            final FilterChainContext ctx,
            Buffer inputBuffer,
            final long timeoutMillis) throws IOException {
        
        final Connection connection = ctx.getConnection();
        final SSLEngine sslEngine = sslCtx.getSslEngine();
        
        final Buffer tmpAppBuffer = allocateOutputBuffer(sslCtx.getAppBufferSize());
        
        final long oldReadTimeout = connection.getBlockingReadTimeout(TimeUnit.MILLISECONDS);
        
        try {
            connection.setBlockingReadTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
            
            inputBuffer = makeInputRemainder(sslCtx, ctx,
                    doHandshakeStep(sslCtx, ctx, inputBuffer, tmpAppBuffer));

            while (isHandshaking(sslEngine)) {
                final ReadResult rr = ctx.read();
                final Buffer newBuf = (Buffer) rr.getMessage();
                inputBuffer = Buffers.appendBuffers(ctx.getMemoryManager(),
                        inputBuffer, newBuf);
                inputBuffer = makeInputRemainder(sslCtx, ctx,
                        doHandshakeStep(sslCtx, ctx, inputBuffer, tmpAppBuffer));
            }
        } finally {
            tmpAppBuffer.dispose();
            connection.setBlockingReadTimeout(oldReadTimeout, TimeUnit.MILLISECONDS);
        }
        
        return inputBuffer;
    }

    protected Buffer doHandshakeStep(final SSLConnectionContext sslCtx,
                                     final FilterChainContext ctx,
                                     Buffer inputBuffer) throws IOException {
        return doHandshakeStep(sslCtx, ctx, inputBuffer, null);
    }
            
    protected Buffer doHandshakeStep(final SSLConnectionContext sslCtx,
                                     final FilterChainContext ctx,
                                     Buffer inputBuffer,
                                     final Buffer tmpAppBuffer0)
            throws IOException {

        final SSLEngine sslEngine = sslCtx.getSslEngine();
        final Connection connection = ctx.getConnection();
        
        final boolean isLoggingFinest = LOGGER.isLoggable(Level.FINEST);
        Buffer tmpInputToDispose = null;
        Buffer tmpNetBuffer = null;
        
        Buffer tmpAppBuffer = tmpAppBuffer0;
        
        try {
            HandshakeStatus handshakeStatus = sslEngine.getHandshakeStatus();

            _exitWhile:
            
            while (true) {

                if (isLoggingFinest) {
                    LOGGER.log(Level.FINEST, "Loop Engine: {0} handshakeStatus={1}",
                            new Object[]{sslEngine, sslEngine.getHandshakeStatus()});
                }

                switch (handshakeStatus) {
                    case NEED_UNWRAP: {

                        if (isLoggingFinest) {
                            LOGGER.log(Level.FINEST, "NEED_UNWRAP Engine: {0}", sslEngine);
                        }

                        if (inputBuffer == null || !inputBuffer.hasRemaining()) {
                            break _exitWhile;
                        }

                        final int expectedLength = getSSLPacketSize(inputBuffer);
                        if (expectedLength == -1
                                || inputBuffer.remaining() < expectedLength) {
                            break _exitWhile;
                        }
                        
                        if (tmpAppBuffer == null) {
                            tmpAppBuffer = allocateOutputBuffer(sslCtx.getAppBufferSize());
                        }
                        
                        final SSLEngineResult sslEngineResult =
                                handshakeUnwrap(sslCtx, inputBuffer, tmpAppBuffer);

                        if (!inputBuffer.hasRemaining()) {
                            tmpInputToDispose = inputBuffer;
                            inputBuffer = null;
                        }

                        final SSLEngineResult.Status status = sslEngineResult.getStatus();

                        if (status == Status.BUFFER_UNDERFLOW ||
                                status == Status.BUFFER_OVERFLOW) {
                            throw new SSLException("SSL unwrap error: " + status);
                        }

                        handshakeStatus = sslEngine.getHandshakeStatus();
                        break;
                    }

                    case NEED_WRAP: {
                        if (isLoggingFinest) {
                            LOGGER.log(Level.FINEST, "NEED_WRAP Engine: {0}", sslEngine);
                        }

                        tmpNetBuffer = handshakeWrap(
                                connection, sslCtx, tmpNetBuffer);
                        handshakeStatus = sslEngine.getHandshakeStatus();

                        break;
                    }

                    case NEED_TASK: {
                        if (isLoggingFinest) {
                            LOGGER.log(Level.FINEST, "NEED_TASK Engine: {0}", sslEngine);
                        }
                        executeDelegatedTask(sslEngine);
                        handshakeStatus = sslEngine.getHandshakeStatus();
                        break;
                    }

                    case FINISHED:
                    case NOT_HANDSHAKING: {
                        break _exitWhile;
                    }
                }

                if (handshakeStatus == HandshakeStatus.FINISHED) {
                    break _exitWhile;
                }
            }
        } catch (IOException ioe) {
            notifyHandshakeFailed(connection, ioe);
            throw ioe;
        } finally {
            if (tmpAppBuffer0 == null && tmpAppBuffer != null) {
                tmpAppBuffer.dispose();
            }
            
            if (tmpInputToDispose != null) {
                tmpInputToDispose.tryDispose();
                inputBuffer = null;
            } else if (inputBuffer != null) {
                inputBuffer.shrink();
            }
            
            if (tmpNetBuffer != null) {
                if (inputBuffer != null) {
                    inputBuffer = makeInputRemainder(sslCtx, ctx, inputBuffer);
                }
                
                ctx.write(tmpNetBuffer);
            }
        }
        
        return inputBuffer;
    }
    
    /**
     * Performs an SSL renegotiation.
     *
     * @param sslCtx the {@link SSLConnectionContext} associated with this
     *  this renegotiation request.
     * @param context the {@link FilterChainContext} associated with this
     *  this renegotiation request.
     *
     * @throws IOException if an error occurs during SSL renegotiation.
     */
    protected void renegotiate(final SSLConnectionContext sslCtx,
                               final FilterChainContext context)
                               throws IOException {

        final SSLEngine sslEngine = sslCtx.getSslEngine();
        if (sslEngine.getWantClientAuth() && !renegotiateOnClientAuthWant) {
            return;
        }
        final boolean authConfigured =
                (sslEngine.getWantClientAuth()
                        || sslEngine.getNeedClientAuth());
        if (!authConfigured) {
            sslEngine.setNeedClientAuth(true);
        }
        final Connection c = context.getConnection();
        sslEngine.getSession().invalidate();

        try {
            sslEngine.beginHandshake();
        } catch (SSLHandshakeException e) {
            // If we catch SSLHandshakeException at this point it may be due
            // to an older SSL peer that hasn't made its SSL/TLS renegotiation
            // secure.  This will be the case with Oracle's VM older than
            // 1.6.0_22 or native applications using OpenSSL libraries
            // older than 0.9.8m.
            //
            // What we're trying to accomplish here is an attempt to detect
            // this issue and log a useful message for the end user instead
            // of an obscure exception stack trace in the server's log.
            // Note that this probably will only work on Oracle's VM.
            if (e.toString().toLowerCase().contains("insecure renegotiation")) {
                if (LOGGER.isLoggable(Level.SEVERE)) {
                    LOGGER.severe("Secure SSL/TLS renegotiation is not "
                            + "supported by the peer.  This is most likely due"
                            + " to the peer using an older SSL/TLS "
                            + "implementation that does not implement RFC 5746.");
                }
                // we could return null here and let the caller
                // decided what to do, but since the SSLEngine will
                // close the channel making further actions useless,
                // we'll report the entire cause.
            }
            throw e;
        }
        
        try {
            rehandshake(context, sslCtx);
        } finally {
            if (!authConfigured) {
                sslEngine.setNeedClientAuth(false);
            }
        }
    }

    private Buffer rehandshake(final FilterChainContext context,
            final SSLConnectionContext sslCtx) throws SSLException {
        final Connection c = context.getConnection();
        
        notifyHandshakeStart(c);

        try {
            return doHandshakeSync(sslCtx, context, null, handshakeTimeoutMillis);

        } catch (Throwable t) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Error during handshake", t);
            }
        }
        
        return null;
    }

    /**
     * <p>
     * Obtains the certificate chain for this SSL session.  If no certificates
     * are available, and <code>needClientAuth</code> is true, an SSL renegotiation
     * will be be triggered to request the certificates from the client.
     * </p>
     *
     * @param sslCtx the {@link SSLConnectionContext} associated with this
     *  certificate request.
     * @param context the {@link FilterChainContext} associated with this
     *  this certificate request.
     * @param needClientAuth determines whether or not SSL renegotiation will
     *  be attempted to obtain the certificate chain.
     *
     * @return the certificate chain as an <code>Object[]</code>.  If no
     *  certificate chain can be determined, this method will return
     *  <code>null</code>.
     *
     * @throws IOException if an error occurs during renegotiation.
     */
    protected Object[] getPeerCertificateChain(final SSLConnectionContext sslCtx,
                                               final FilterChainContext context,
                                               final boolean needClientAuth)
    throws IOException {

        Certificate[] certs = getPeerCertificates(sslCtx);
        if (certs != null) {
            return certs;
        }

        if (needClientAuth) {
            renegotiate(sslCtx, context);
        }

        certs = getPeerCertificates(sslCtx);

        if (certs == null) {
            return null;
        }

        X509Certificate[] x509Certs = extractX509Certs(certs);

        if (x509Certs == null || x509Certs.length < 1) {
            return null;
        }

        return x509Certs;
    }

    private FilterChainContext obtainProtocolChainContext(
            final FilterChainContext ctx,
            final FilterChain completeProtocolFilterChain) {

        final FilterChainContext newFilterChainContext =
                completeProtocolFilterChain.obtainFilterChainContext(
                        ctx.getConnection(),
                        ctx.getStartIdx(),
                        completeProtocolFilterChain.size(),
                        ctx.getFilterIdx());

        newFilterChainContext.setAddressHolder(ctx.getAddressHolder());
        newFilterChainContext.setMessage(ctx.getMessage());
        newFilterChainContext.getInternalContext().setEvent(
                IOEvent.READ,
                new InternalProcessingHandler(ctx));

        return newFilterChainContext;
    }


    // --------------------------------------------------------- Private Methods

    private X509Certificate[] extractX509Certs(final Certificate[] certs) {
        final X509Certificate[] x509Certs = new X509Certificate[certs.length];
        for(int i = 0, len = certs.length; i < len; i++) {
            if( certs[i] instanceof X509Certificate ) {
                x509Certs[i] = (X509Certificate)certs[i];
            } else {
                try {
                    final byte [] buffer = certs[i].getEncoded();
                    final CertificateFactory cf =
                            CertificateFactory.getInstance("X.509");
                    ByteArrayInputStream stream = new ByteArrayInputStream(buffer);
                    x509Certs[i] = (X509Certificate)
                    cf.generateCertificate(stream);
                } catch(Exception ex) {
                    LOGGER.log(Level.INFO,
                               "Error translating cert " + certs[i],
                               ex);
                    return null;
                }
            }

            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Cert #{0} = {1}", new Object[] {i, x509Certs[i]});
            }
        }
        return x509Certs;
    }

    private Certificate[] getPeerCertificates(final SSLConnectionContext sslCtx) {
        try {
            return sslCtx.getSslEngine().getSession().getPeerCertificates();
        } catch( Throwable t ) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE,"Error getting client certs", t);
            }
            return null;
        }
    }

    protected void notifyHandshakeStart(final Connection connection) {
        if (!handshakeListeners.isEmpty()) {
            for (final HandshakeListener listener : handshakeListeners) {
                listener.onStart(connection);
            }
        }
    }
    
    protected void notifyHandshakeComplete(final Connection<?> connection,
                                          final SSLEngine sslEngine) {

        if (!handshakeListeners.isEmpty()) {
            for (final HandshakeListener listener : handshakeListeners) {
                listener.onComplete(connection);
            }
        }
    }

    protected void notifyHandshakeFailed(final Connection connection,
            final Throwable t) {
    }
    
    // ----------------------------------------------------------- Inner Classes

    public static class CertificateEvent implements Event {

        private static final String TYPE = "CERT_EVENT";

        private Object[] certs;

        private final boolean needClientAuth;


        // -------------------------------------------------------- Constructors


        public CertificateEvent(final boolean needClientAuth) {
            this.needClientAuth = needClientAuth;
        }


        // --------------------------------------- Methods from FilterChainEvent


        @Override
        public final Object type() {
            return TYPE;
        }


        // ------------------------------------------------------ Public Methods


        public Object[] getCertificates() {
            return certs;
        }

    } // END CertificateEvent

    private class InternalProcessingHandler extends EventProcessingHandler.Adapter {
        private final FilterChainContext parentContext;

        private InternalProcessingHandler(final FilterChainContext parentContext) {
            this.parentContext = parentContext;
        }

        @Override
        public void onComplete(final Context context) throws IOException {
            parentContext.resume(parentContext.getStopAction());
        }

    } // END InternalProcessingHandler
    
    public static interface HandshakeListener {
        public void onStart(Connection connection);
        public void onComplete(Connection connection);
    }
    
    private final class SSLTransportFilterWrapper extends TransportFilter {
        private final TransportFilter transportFilter;

        public SSLTransportFilterWrapper(final TransportFilter transportFilter) {
            this.transportFilter = transportFilter;
        }
        
        @Override
        public NextAction handleAccept(FilterChainContext ctx) throws Exception {
            return transportFilter.handleAccept(ctx);
        }

        @Override
        public NextAction handleConnect(FilterChainContext ctx) throws Exception {
            return transportFilter.handleConnect(ctx);
        }

        @Override
        public NextAction handleRead(final FilterChainContext ctx) throws Exception {
            final Connection connection = ctx.getConnection();
            final SSLConnectionContext sslCtx =
                    getSslConnectionContext(connection);
            
            if (sslCtx.getSslEngine() == null) {
                final SSLEngine sslEngine = serverSSLEngineConfigurator.createSSLEngine();
                sslEngine.beginHandshake();
                sslCtx.configure(sslEngine);
                notifyHandshakeStart(connection);
            }
            
            ctx.setMessage(allowDispose(allocateInputBuffer(sslCtx)));
            
            return transportFilter.handleRead(ctx);
        }

        @Override
        public NextAction handleWrite(FilterChainContext ctx) throws Exception {
            return transportFilter.handleWrite(ctx);
        }

        @Override
        public NextAction handleEvent(FilterChainContext ctx, Event event) throws Exception {
            return transportFilter.handleEvent(ctx, event);
        }

        @Override
        public NextAction handleClose(FilterChainContext ctx) throws Exception {
            return transportFilter.handleClose(ctx);
        }

        @Override
        public void onFilterChainConstructed(FilterChain filterChain) {
            transportFilter.onFilterChainConstructed(filterChain);
        }

        @Override
        public void exceptionOccurred(FilterChainContext ctx, Throwable error) {
            transportFilter.exceptionOccurred(ctx, error);
        }
    }
    
    private static final class OnWriteCopyCloner extends LifeCycleHandler.Adapter {

        private final LifeCycleHandler parentHandler;

        public OnWriteCopyCloner(final LifeCycleHandler parentHandler) {
            this.parentHandler = parentHandler;
        }
                
        @Override
        public WritableMessage onThreadContextSwitch(final Connection connection,
                WritableMessage message) {
            if (parentHandler != null) {
                message = parentHandler.onThreadContextSwitch(connection, message);
            }
            
            final Buffer originalBuffer = (Buffer) message;
            
            final SSLConnectionContext sslCtx = getSslConnectionContext(connection);

            final int copyThreshold = sslCtx.getNetBufferSize() / 2;

            final Buffer lastOutputBuffer = sslCtx.resetLastOutputBuffer();

            final int totalRemaining = originalBuffer.remaining();

            if (totalRemaining < copyThreshold) {
                return move(connection.getTransport().getMemoryManager(),
                        originalBuffer);
            }
            if (lastOutputBuffer.remaining() < copyThreshold) {
                final Buffer tmpBuf =
                        copy(connection.getTransport().getMemoryManager(),
                        originalBuffer);

                if (originalBuffer.isComposite()) {
                    ((CompositeBuffer) originalBuffer).replace(
                            lastOutputBuffer, tmpBuf);
                } else {
                    assert originalBuffer == lastOutputBuffer;
                }

                lastOutputBuffer.tryDispose();

                return tmpBuf;
            }


            return originalBuffer;
        }
    }
}
