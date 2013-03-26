package org.typesafe.netty.http.pipelining;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.http.*;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.TimerTask;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.jboss.netty.buffer.ChannelBuffers.EMPTY_BUFFER;
import static org.jboss.netty.buffer.ChannelBuffers.copiedBuffer;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.*;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Values.CHUNKED;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Values.KEEP_ALIVE;
import static org.jboss.netty.handler.codec.http.HttpResponseStatus.*;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.jboss.netty.util.CharsetUtil.*;
import static org.junit.Assert.assertTrue;

public class HttpPipeliningHandlerTest {

    private static final long RESPONSE_TIMEOUT = 1000L;
    private static final long CONNECTION_TIMEOUT = 1000L;
    private static final String CONTENT_TYPE_TEXT = "text/plain; charset=UTF-8";
    private static final InetSocketAddress HOST_ADDR = new InetSocketAddress("127.0.0.1", 9080);
    private static final String PATH1 = "/1";
    private static final String PATH2 = "/2";
    private static final String SOME_RESPONSE_TEXT = "some response for ";

    private ClientBootstrap clientBootstrap;
    private ServerBootstrap serverBootstrap;

    private CountDownLatch responsesIn;
    private final List<String> responses = new ArrayList<String>(2);

    private HashedWheelTimer timer;

    @Before
    public void setUp() {
        clientBootstrap = new ClientBootstrap(
                new NioClientSocketChannelFactory(
                        Executors.newSingleThreadExecutor(),
                        Executors.newSingleThreadExecutor()));

        clientBootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            @Override
            public ChannelPipeline getPipeline() throws Exception {
                return Channels.pipeline(
                        new HttpClientCodec(),
                        new ClientHandler()
                );
            }
        });

        serverBootstrap = new ServerBootstrap(
                new NioServerSocketChannelFactory(
                        Executors.newCachedThreadPool(),
                        Executors.newCachedThreadPool()));

        serverBootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            @Override
            public ChannelPipeline getPipeline() throws Exception {
                return Channels.pipeline(
                        new HttpRequestDecoder(),
                        new HttpResponseEncoder(),
                        new HttpPipeliningHandler(),
                        new ServerHandler()
                );
            }
        });

        serverBootstrap.bind(HOST_ADDR);

        timer = new HashedWheelTimer();
    }

    @After
    public void shutDown() {
        timer.stop();

        serverBootstrap.shutdown();
        serverBootstrap.releaseExternalResources();
        clientBootstrap.shutdown();
        clientBootstrap.releaseExternalResources();
    }

    @Test
    public void shouldReturnMessagesInOrder() throws InterruptedException {
        responsesIn = new CountDownLatch(2);
        responses.clear();

        final ChannelFuture connectionFuture = clientBootstrap.connect(HOST_ADDR);

        assertTrue(connectionFuture.await(CONNECTION_TIMEOUT));
        final Channel clientChannel = connectionFuture.getChannel();

        final HttpRequest request1 = new DefaultHttpRequest(
                HTTP_1_1, HttpMethod.GET, PATH1);
        request1.setHeader(HOST, HOST_ADDR.toString());

        final HttpRequest request2 = new DefaultHttpRequest(
                HTTP_1_1, HttpMethod.GET, PATH2);
        request2.setHeader(HOST, HOST_ADDR.toString());

        clientChannel.write(request1);
        clientChannel.write(request2);

        responsesIn.await(RESPONSE_TIMEOUT, MILLISECONDS);

        assertTrue(responses.contains(SOME_RESPONSE_TEXT + PATH1));
        assertTrue(responses.contains(SOME_RESPONSE_TEXT + PATH2));
    }

    public class ClientHandler extends SimpleChannelUpstreamHandler {
        @Override
        public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent e) {
            final Object message = e.getMessage();
            if (message instanceof HttpChunk) {
                final HttpChunk response = (HttpChunk) e.getMessage();
                if (response.getContent().readable()) {
                    responses.add(response.getContent().toString(UTF_8));
                } else {
                    responsesIn.countDown();
                }
            }
        }
    }

    public class ServerHandler extends SimpleChannelUpstreamHandler {
        private final AtomicBoolean sendFinalChunk = new AtomicBoolean(false);

        @Override
        public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent e) throws InterruptedException {
            final HttpRequest request = (HttpRequest) e.getMessage();

            final int sequence = ((OrderedUpstreamMessageEvent) e).getSequence();
            final String uri = request.getUri();

            ctx.sendDownstream(new OrderedDownstreamMessageEvent(sequence, false, ctx.getChannel(),
                    Channels.future(ctx.getChannel()), createInitialChunk(), e.getRemoteAddress()));

            timer.newTimeout(new ChunkWriter(ctx, e, uri, sequence, 0), 0, MILLISECONDS);
        }

        private class ChunkWriter implements TimerTask {
            private final ChannelHandlerContext ctx;
            private final MessageEvent e;
            private final String uri;
            private final int sequence;
            private final int count;

            public ChunkWriter(final ChannelHandlerContext ctx, final MessageEvent e, final String uri,
                               final int sequence, final int count) {
                this.ctx = ctx;
                this.e = e;
                this.uri = uri;
                this.sequence = sequence;
                this.count = count;
            }

            @Override
            public void run(final Timeout timeout) {
                if (sendFinalChunk.get() && count > 0) {
                    ctx.sendDownstream(new OrderedDownstreamMessageEvent(sequence, true, ctx.getChannel(),
                            Channels.future(ctx.getChannel()), createFinalChunk(), e.getRemoteAddress()));
                } else {
                    ctx.sendDownstream(new OrderedDownstreamMessageEvent(sequence, false, ctx.getChannel(),
                            Channels.future(ctx.getChannel()), createChunk(uri), e.getRemoteAddress()));
                    timer.newTimeout(new ChunkWriter(ctx, e, uri, sequence, count + 1), 0, MILLISECONDS);

                    if (uri.equals(PATH2)) {
                        sendFinalChunk.set(true);
                    }
                }
            }

        }

        private HttpResponse createInitialChunk() {
            final HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
            response.setHeader(CONTENT_TYPE, CONTENT_TYPE_TEXT);
            response.setHeader(CONNECTION, KEEP_ALIVE);
            response.setHeader(TRANSFER_ENCODING, CHUNKED);
            return response;
        }

        private HttpChunk createChunk(final String uri) {
            return new DefaultHttpChunk(copiedBuffer(SOME_RESPONSE_TEXT + uri, UTF_8));
        }

        private HttpChunk createFinalChunk() {
            return new DefaultHttpChunk(EMPTY_BUFFER);
        }

    }
}