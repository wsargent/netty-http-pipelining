package org.typesafe.netty.http.pipelining;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.DownstreamMessageEvent;

import java.net.SocketAddress;

/**
 * Permits downstream message events to be ordered and signalled as to whether more are to come for a given sequence.
 *
 * @author Christopher Hunt
 */
public class OrderedDownstreamMessageEvent extends DownstreamMessageEvent {
    final int sequence;
    final boolean last;

    /**
     * Convenience constructor signifying that this downstream event is the last one for the given sequence.
     */
    public OrderedDownstreamMessageEvent(final int sequence, final Channel channel,
                                         final ChannelFuture future, final Object message,
                                         final SocketAddress remoteAddress) {
        this(sequence, true, channel, future, message, remoteAddress);
    }

    /**
     * @param sequence the sequence number that this reply relates to in terms of its OrderedUpstreamMessageEvent
     * @param last     when set to true this indicates that there are no more responses to be received for the
     *                 original OrderedUpstreamMessageEvent
     */
    public OrderedDownstreamMessageEvent(final int sequence, boolean last, final Channel channel,
                                         final ChannelFuture future, final Object message,
                                         final SocketAddress remoteAddress) {
        super(channel, future, message, remoteAddress);
        this.sequence = sequence;
        this.last = last;
    }

    public int getSequence() {
        return sequence;
    }

    public boolean isLast() {
        return last;
    }

}