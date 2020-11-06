/*
 * Copyright @ 2017 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge;

import com.google.common.collect.*;
import org.jetbrains.annotations.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.cc.*;
import org.jitsi.videobridge.datachannel.*;
import org.jitsi.videobridge.datachannel.protocol.*;
import org.jitsi.videobridge.message.*;
import org.jitsi.videobridge.octo.*;
import org.jitsi.videobridge.websocket.*;
import org.json.simple.*;

import java.lang.ref.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Handles the functionality related to sending and receiving COLIBRI messages
 * for an {@link Endpoint}. Supports two underlying transport mechanisms --
 * WebRTC data channels and {@code WebSocket}s.
 *
 * @author Boris Grozev
 */
class EndpointMessageTransport
    extends AbstractEndpointMessageTransport<Endpoint>
    implements DataChannelStack.DataChannelMessageListener,
        ColibriWebSocket.EventHandler
{
    /**
     * The last accepted web-socket by this instance, if any.
     */
    private ColibriWebSocket webSocket;

    /**
     * User to synchronize access to {@link #webSocket}
     */
    private final Object webSocketSyncRoot = new Object();

    /**
     * Whether the last active transport channel (i.e. the last to receive a
     * message from the remote endpoint) was the web socket (if {@code true}),
     * or the WebRTC data channel (if {@code false}).
     */
    private boolean webSocketLastActive = false;

    private WeakReference<DataChannel> dataChannel = new WeakReference<>(null);

    private final Supplier<Videobridge.Statistics> statisticsSupplier;

    private final EndpointMessageTransportEventHandler eventHandler;

    private final AtomicInteger numOutgoingMessagesDropped = new AtomicInteger(0);

    /**
     * The compatibility layer that translates selected, pinned and max
     * resolution messages into video constraints.
     */
    private final VideoConstraintsCompatibility videoConstraintsCompatibility = new VideoConstraintsCompatibility();

    /**
     * The number of sent message by type.
     */
    private final Map<String, AtomicLong> sentMessagesCounts = new ConcurrentHashMap<>();

    @NotNull
    private final Endpoint endpoint;

    /**
     * Initializes a new {@link EndpointMessageTransport} instance.
     * @param endpoint the associated {@link Endpoint}.
     * @param statisticsSupplier a {@link Supplier} which returns an instance
     *                      of {@link Videobridge.Statistics} which will
     *                      be used to update any stats generated by this
     *                      class
     */
    EndpointMessageTransport(
        @NotNull Endpoint endpoint,
        Supplier<Videobridge.Statistics> statisticsSupplier,
        EndpointMessageTransportEventHandler eventHandler,
        Logger parentLogger)
    {
        super(parentLogger);
        this.endpoint = endpoint;
        this.statisticsSupplier = statisticsSupplier;
        this.eventHandler = eventHandler;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void notifyTransportChannelConnected()
    {
        eventHandler.endpointMessageTransportConnected(endpoint);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BridgeChannelMessage clientHello(ClientHelloMessage message)
    {
        // ClientHello was introduced for functional testing purposes. It
        // triggers a ServerHello response from Videobridge. The exchange
        // reveals (to the client) that the transport channel between the
        // remote endpoint and the Videobridge is operational.
        // We take care to send the reply using the same transport channel on
        // which we received the request..
        return new ServerHelloMessage();
    }

    @Override
    public void unhandledMessage(BridgeChannelMessage message)
    {
        logger.warn("Received a message with an unexpected type: " + message.getType());
    }

    /**
     * Sends a string via a particular transport channel.
     * @param dst the transport channel.
     * @param message the message to send.
     */
    protected void sendMessage(Object dst, BridgeChannelMessage message)
    {
        super.sendMessage(dst, message); // Log message

        if (dst instanceof ColibriWebSocket)
        {
            sendMessage((ColibriWebSocket) dst, message);
        }
        else if (dst instanceof DataChannel)
        {
            sendMessage((DataChannel)dst, message);
        }
        else
        {
            throw new IllegalArgumentException("unknown transport:" + dst);
        }
    }

    /**
     * Sends a string via a particular {@link DataChannel}.
     * @param dst the data channel to send through.
     * @param message the message to send.
     */
    private void sendMessage(DataChannel dst, BridgeChannelMessage message)
    {
        dst.sendString(message.toJson());
        statisticsSupplier.get().totalDataChannelMessagesSent.incrementAndGet();
    }

    /**
     * Sends a string via a particular {@link ColibriWebSocket} instance.
     * @param dst the {@link ColibriWebSocket} through which to send the message.
     * @param message the message to send.
     */
    private void sendMessage(ColibriWebSocket dst, BridgeChannelMessage message)
    {
        // We'll use the async version of sendString since this may be called
        // from multiple threads.  It's just fire-and-forget though, so we
        // don't wait on the result
        dst.getRemote().sendStringByFuture(message.toJson());
        statisticsSupplier.get().totalColibriWebSocketMessagesSent.incrementAndGet();
    }

    @Override
    public void onDataChannelMessage(DataChannelMessage dataChannelMessage)
    {
        webSocketLastActive = false;
        statisticsSupplier.get().totalDataChannelMessagesReceived.incrementAndGet();

        if (dataChannelMessage instanceof DataChannelStringMessage)
        {
            DataChannelStringMessage dataChannelStringMessage = (DataChannelStringMessage)dataChannelMessage;
            onMessage(dataChannel.get(), dataChannelStringMessage.data);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void sendMessage(@NotNull BridgeChannelMessage msg)
    {
        Object dst = getActiveTransportChannel();
        if (dst == null)
        {
            logger.debug("No available transport channel, can't send a message");
            numOutgoingMessagesDropped.incrementAndGet();
        }
        else
        {
            sentMessagesCounts.computeIfAbsent(
                    msg.getClass().getSimpleName(),
                    (k) -> new AtomicLong()).incrementAndGet();
            sendMessage(dst, msg);
        }
    }

    /**
     * @return the active transport channel for this
     * {@link EndpointMessageTransport} (either the {@link #webSocket}, or
     * the WebRTC data channel represented by a {@link DataChannel}).
     * </p>
     * The "active" channel is determined based on what channels are available,
     * and which one was the last to receive data. That is, if only one channel
     * is available, it will be returned. If two channels are available, the
     * last one to have received data will be returned. Otherwise, {@code null}
     * will be returned.
     */
    //TODO(brian): seems like it'd be nice to have the websocket and datachannel
    // share a common parent class (or, at least, have a class that is returned
    // here and provides a common API but can wrap either a websocket or
    // datachannel)
    private Object getActiveTransportChannel()
    {
        DataChannel dataChannel = this.dataChannel.get();
        ColibriWebSocket webSocket = this.webSocket;

        Object dst = null;
        if (webSocketLastActive)
        {
            dst = webSocket;
        }

        // Either the socket was not the last active channel,
        // or it has been closed.
        if (dst == null)
        {
            if (dataChannel != null && dataChannel.isReady())
            {
                dst = dataChannel;
            }
        }

        // Maybe the WebRTC data channel is the last active, but it is not
        // currently available. If so, and a web-socket is available -- use it.
        if (dst == null && webSocket != null)
        {
            dst = webSocket;
        }

        return dst;
    }

    @Override
    public boolean isConnected()
    {
        return getActiveTransportChannel() != null;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public void webSocketConnected(ColibriWebSocket ws)
    {
        synchronized (webSocketSyncRoot)
        {
            // If we already have a web-socket, discard it and use the new one.
            if (webSocket != null)
            {
                webSocket.getSession().close(200, "replaced");
            }

            webSocket = ws;
            webSocketLastActive = true;
            sendMessage(ws, new ServerHelloMessage());
        }

        notifyTransportChannelConnected();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void webSocketClosed(ColibriWebSocket ws, int statusCode, String reason)
    {
        synchronized (webSocketSyncRoot)
        {
            if (ws != null && ws.equals(webSocket))
            {
                webSocket = null;
                webSocketLastActive = false;
                logger.debug(() -> "Web socket closed, statusCode " + statusCode + " ( " + reason + ").");
            }
        }

    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void close()
    {
        synchronized (webSocketSyncRoot)
        {
            if (webSocket != null)
            {
                // 410 Gone indicates that the resource requested is no longer
                // available and will not be available again.
                webSocket.getSession().close(410, "replaced");
                webSocket = null;
                logger.debug(() -> "Endpoint expired, closed colibri web-socket.");
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void webSocketTextReceived(ColibriWebSocket ws, String message)
    {
        if (ws == null || !ws.equals(webSocket))
        {
            logger.warn("Received text from an unknown web socket.");
            return;
        }

        statisticsSupplier.get().totalColibriWebSocketMessagesReceived.incrementAndGet();

        webSocketLastActive = true;
        onMessage(ws, message);
    }

    /**
     * Sets the data channel for this endpoint.
     * @param dataChannel the {@link DataChannel} to use for this transport
     */
    void setDataChannel(DataChannel dataChannel)
    {
        DataChannel prevDataChannel = this.dataChannel.get();
        if (prevDataChannel == null)
        {
            this.dataChannel = new WeakReference<>(dataChannel);
            // We install the handler first, otherwise the 'ready' might fire after we check it but before we
            //  install the handler
            dataChannel.onDataChannelEvents(this::notifyTransportChannelConnected);
            if (dataChannel.isReady())
            {
                notifyTransportChannelConnected();
            }
            dataChannel.onDataChannelMessage(this);
        }
        else if (prevDataChannel == dataChannel)
        {
            //TODO: i think we should be able to ensure this doesn't happen,
            // so throwing for now.  if there's a good
            // reason for this, we can make this a no-op
            throw new Error("Re-setting the same data channel");
        }
        else
        {
            throw new Error("Overwriting a previous data channel!");
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public JSONObject getDebugState()
    {
        JSONObject debugState = super.getDebugState();
        debugState.put("numOutgoingMessagesDropped", numOutgoingMessagesDropped.get());

        JSONObject sentCounts = new JSONObject();
        sentCounts.putAll(sentMessagesCounts);
        debugState.put("sent_counts", sentCounts);

        debugState.put("video_constraints_compatibility", videoConstraintsCompatibility.getDebugState());

        return debugState;
    }

    /**
     * Notifies this {@code Endpoint} that a {@link PinnedEndpointMessage}
     * has been received.
     *
     * @param message the message that was received.
     */
    @Override
    public BridgeChannelMessage pinnedEndpoint(PinnedEndpointMessage message)
    {
        String newPinnedEndpointID = message.getPinnedEndpoint();

        List<String> newPinnedIDs =
                isBlank(newPinnedEndpointID) ?
                        Collections.emptyList() :
                        Collections.singletonList(newPinnedEndpointID);

        pinnedEndpoints(new PinnedEndpointsMessage(newPinnedIDs));
        return null;
    }

    /**
     * Notifies this {@code Endpoint} that a {@code PinnedEndpointsChangedEvent}
     * has been received.
     *
     * @param message the message that was received.
     */
    @Override
    public BridgeChannelMessage pinnedEndpoints(PinnedEndpointsMessage message)
    {
        Set<String> newPinnedEndpoints = new HashSet<>(message.getPinnedEndpoints());

        logger.debug(() -> "Pinned " + newPinnedEndpoints);

        videoConstraintsCompatibility.setPinnedEndpoints(newPinnedEndpoints);
        setSenderVideoConstraints(videoConstraintsCompatibility.computeVideoConstraints());

        return null;
    }

    /**
     * Notifies this {@code Endpoint} that a {@link SelectedEndpointsMessage}
     * has been received.
     *
     * @param message the message that was received.
     */
    @Override
    public BridgeChannelMessage selectedEndpoint(SelectedEndpointMessage message)
    {
        String newSelectedEndpointID = message.getSelectedEndpoint();

        List<String> newSelectedIDs =
                isBlank(newSelectedEndpointID) ?
                        Collections.emptyList() :
                        Collections.singletonList(newSelectedEndpointID);

        selectedEndpoints(new SelectedEndpointsMessage(newSelectedIDs));
        return null;
    }

    /**
     * Notifies this {@code Endpoint} that a {@link SelectedEndpointsMessage}
     * has been received.
     *
     * @param message the message that was received.
     */
    @Override
    public BridgeChannelMessage selectedEndpoints(SelectedEndpointsMessage message)
    {
        Set<String> newSelectedEndpoints = new HashSet<>(message.getSelectedEndpoints());

        logger.debug(() -> "Selected " + newSelectedEndpoints);
        videoConstraintsCompatibility.setSelectedEndpoints(newSelectedEndpoints);
        setSenderVideoConstraints(videoConstraintsCompatibility.computeVideoConstraints());
        return null;
    }

    /**
     * Sets the sender video constraints of this {@link #endpoint}.
     *
     * @param videoConstraintsMap the sender video constraints of this
     * {@link #endpoint}.
     */
    public void setSenderVideoConstraints(Map<String, VideoConstraints> videoConstraintsMap)
    {
        // Don't "pollute" the video constraints map with constraints for this
        // endpoint.
        videoConstraintsMap.remove(endpoint.getID());

        logger.debug(() -> "New video constraints map: " + videoConstraintsMap);

        endpoint.setSenderVideoConstraints(ImmutableMap.copyOf(videoConstraintsMap));
    }

    /**
     * Notifies this {@code Endpoint} that a
     * {@link ReceiverVideoConstraintMessage} has been received
     *
     * @param message the message that was received.
     */
    @Override
    public BridgeChannelMessage receiverVideoConstraint(ReceiverVideoConstraintMessage message)
    {
        int maxFrameHeight = message.getMaxFrameHeight();
        logger.debug(
                () -> "Received a maxFrameHeight video constraint from " + endpoint.getID() + ": " + maxFrameHeight);

        videoConstraintsCompatibility.setMaxFrameHeight(maxFrameHeight);
        setSenderVideoConstraints(videoConstraintsCompatibility.computeVideoConstraints());

        return null;
    }

    /**
     * Notifies this {@code Endpoint} that a {@link LastNMessage} has been
     * received.
     *
     * @param message the message that was received.
     */
    @Override
    public BridgeChannelMessage lastN(LastNMessage message)
    {
        endpoint.setLastN(message.getLastN());

        return null;
    }

    /**
     * Handles an opaque message from this {@code Endpoint} that should be forwarded to either: a) another client in
     * this conference (1:1 message) or b) all other clients in this conference (broadcast message).
     *
     * @param message the message that was received from the endpoint.
     */
    @Override
    public BridgeChannelMessage endpointMessage(EndpointMessage message)
    {
        // First insert/overwrite the "from" to prevent spoofing.
        String from = endpoint.getID();
        message.setFrom(from);

        Conference conference = endpoint.getConference();

        if (conference == null || conference.isExpired())
        {
            logger.warn("Unable to send EndpointMessage, conference is null or expired");
            return null;
        }

        boolean sendToOcto;

        List<AbstractEndpoint> targets;
        if (message.isBroadcast())
        {
            // Broadcast message to all local endpoints + octo.
            targets = new LinkedList<>(conference.getLocalEndpoints());
            targets.remove(endpoint);
            sendToOcto = true;
        }
        else
        {
            // 1:1 message
            String to = message.getTo();

            AbstractEndpoint targetEndpoint = conference.getEndpoint(to);
            if (targetEndpoint instanceof OctoEndpoint)
            {
                targets = Collections.emptyList();
                sendToOcto = true;
            }
            else if (targetEndpoint != null)
            {
                targets = Collections.singletonList(targetEndpoint);
                sendToOcto = false;
            }
            else
            {
                logger.warn("Unable to find endpoint to send EndpointMessage to: " + to);
                return null;
            }
        }

        conference.sendMessage(message, targets, sendToOcto);
        return null;
    }
}
