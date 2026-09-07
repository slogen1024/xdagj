/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2020-2030 The XdagJ Developers
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.xdag.rpc.server.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Test;

/**
 * Audit round 2, R7: browsers cannot set an {@code Authorization} header on a WebSocket upgrade, so
 * the bearer token is also accepted as a {@code token} query parameter on the request URI (constant-
 * time compared like the header). The header path and the reject path are unchanged.
 */
public class AuthHandlerTest {

    private static FullHttpRequest get(String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
    }

    @Test
    public void a_matching_token_query_parameter_authorizes_the_upgrade_request() {
        EmbeddedChannel ch = new EmbeddedChannel(new AuthHandler("s3cret"));
        FullHttpRequest req = get("/?token=s3cret");
        ch.writeInbound(req);
        assertNotNull("request passed through to the next handler", ch.readInbound());
        assertNull(ch.readOutbound());
    }

    @Test
    public void a_wrong_token_query_parameter_is_rejected_with_401() {
        EmbeddedChannel ch = new EmbeddedChannel(new AuthHandler("s3cret"));
        ch.writeInbound(get("/?token=nope"));
        assertNull(ch.readInbound());
        FullHttpResponse response = ch.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.UNAUTHORIZED, response.status());
    }

    @Test
    public void the_authorization_header_still_works_and_a_missing_token_is_rejected() {
        EmbeddedChannel ch = new EmbeddedChannel(new AuthHandler("s3cret"));
        FullHttpRequest withHeader = get("/");
        withHeader.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer s3cret");
        ch.writeInbound(withHeader);
        assertNotNull(ch.readInbound());

        EmbeddedChannel ch2 = new EmbeddedChannel(new AuthHandler("s3cret"));
        ch2.writeInbound(get("/"));
        assertNull(ch2.readInbound());
        assertEquals(HttpResponseStatus.UNAUTHORIZED, ((FullHttpResponse) ch2.readOutbound()).status());
    }
}
