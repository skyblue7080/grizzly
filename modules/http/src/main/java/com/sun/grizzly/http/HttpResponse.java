/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2010 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
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
 *
 */

package com.sun.grizzly.http;

import com.sun.grizzly.http.util.BufferChunk;



/**
 * The {@link HttpHeader} object, which represents HTTP response message.
 *
 * @see HttpHeader
 * @see HttpRequest
 *
 * @author Alexey Stashok
 */
public class HttpResponse extends HttpHeader {
    public static final int NON_PARSED_STATUS = Integer.MIN_VALUE;
    
    // ----------------------------------------------------- Instance Variables

    /**
     * Status code.
     */
    protected int parsedStatusInt = NON_PARSED_STATUS;
    protected BufferChunk statusBC = BufferChunk.newInstance();


    /**
     * Status message.
     */
    private BufferChunk reasonPhraseBC = BufferChunk.newInstance();

    /**
     * Returns {@link HttpResponse} builder.
     *
     * @return {@link Builder}.
     */
    public static Builder builder() {
        return new Builder();
    }

    // ----------------------------------------------------------- Constructors
    protected HttpResponse() {
    }

    // -------------------- State --------------------
    /**
     * Gets the status code for this response as {@link BufferChunk} (avoid
     * the status code parsing}.
     *
     * @return the status code for this response as {@link BufferChunk} (avoid
     * the status code parsing}.
     */
    public BufferChunk getStatusBC() {
        return statusBC;
    }

    /**
     * Gets the status code for this response.
     *
     * @return the status code for this response.
     */
    public int getStatus() {
        if (parsedStatusInt == NON_PARSED_STATUS) {
            parsedStatusInt = Integer.parseInt(statusBC.toString());
        }

        return parsedStatusInt;
    }
    
    /**
     * Sets the status code for this response.
     *
     * @param status the status code for this response.
     */
    public void setStatus(int status) {
        parsedStatusInt = status;
        statusBC.setString(Integer.toString(status));
    }


    /**
     * Gets the status reason phrase for this response as {@link BufferChunk}
     * (avoid creation of a String object}.
     *
     * @return the status reason phrase for this response as {@link BufferChunk}
     * (avoid creation of a String object}.
     */
    public BufferChunk getReasonPhraseBC() {
        return reasonPhraseBC;
    }

    /**
     * Gets the status reason phrase for this response.
     *
     * @return the status reason phrase for this response.
     */
    public String getReasonPhrase() {
        return reasonPhraseBC.toString();
    }


    /**
     * Sets the status reason phrase for this response.
     *
     * @param message the status reason phrase for this response.
     */
    public void setReasonPhrase(String message) {
        reasonPhraseBC.setString(message);
    }

    // --------------------
    
    /**
     * {@inheritDoc}
     */
    @Override
    public void recycle() {
        super.recycle();
        statusBC.recycle();
        reasonPhraseBC.recycle();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public final boolean isRequest() {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(256);
        sb.append("HttpResponse (status=").append(getStatus())
                .append(" reason=").append(getReasonPhrase())
                .append(" protocol=").append(getProtocol())
                .append(" content-length=").append(getContentLength())
                .append(" headers=").append(getHeaders())
                .append(')');
        
        return sb.toString();
    }

    /**
     * <tt>HttpResponse</tt> message builder.
     */
    public static class Builder extends HttpHeader.Builder<Builder> {
        protected Builder() {
            packet = new HttpResponse();
        }

        /**
         * Sets the status code for this response.
         *
         * @param status the status code for this response.
         */
        public Builder status(int status) {
            ((HttpResponse) packet).setStatus(status);
            return this;
        }

        /**
         * Sets the status reason phrase for this response.
         *
         * @param message the status reason phrase for this response.
         */
        public Builder reasonPhrase(String reasonPhrase) {
            ((HttpResponse) packet).setReasonPhrase(reasonPhrase);
            return this;
        }

        /**
         * Build the <tt>HttpResponse</tt> message.
         *
         * @return <tt>HttpResponse</tt>
         */
        public final HttpResponse build() {
            return (HttpResponse) packet;
        }
    }
}
