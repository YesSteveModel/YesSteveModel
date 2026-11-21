package com.elfmcys.ysm.network;

import com.elfmcys.ysm.network.frame.OutboundFrame;
import com.elfmcys.ysm.network.protocol.MessageDirection;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import com.elfmcys.ysm.proto.network.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import us.hebi.quickbuf.ProtoMessage;

/** Observes existing messages without owning state, decoding frames, or changing delivery. */
public final class NetworkDiagnostics {
    private static final boolean ENABLED = System.getenv("YSM_DEBUG_NETWORK") != null;
    private static final Logger LOGGER = LogManager.getLogger("ysm");

    private NetworkDiagnostics() {}

    public static boolean isEnabled() {
        return ENABLED;
    }

    public static void message(String event, MessageDirection direction, ProtoMessage<?> message, String reason) {
        if (!ENABLED)
            return;
        try {
            var spec = ProtocolMessages.REGISTRY.find(message.getClass()).orElse(null);
            log("event=" + event + " direction=" + direction + " layer=message type="
                + message.getClass().getSimpleName() + " protocolId=" + (spec == null ? -1 : spec.id())
                + " protobufBytes=" + message.getSerializedSize() + summary(message) + " reason=" + reason);
        } catch (RuntimeException ignored) {
            // A diagnostic sink or summary failure cannot change a domain or transport result.
        }
    }

    public static void frame(String event, MessageDirection direction, OutboundFrame frame, String path) {
        if (!ENABLED)
            return;
        try {
            frame(event, direction, Byte.toUnsignedInt(frame.borrow().nio().get(0)) >>> 1, frame.size(), path);
        } catch (RuntimeException ignored) {
            // Diagnostic reads do not own the frame's transport outcome.
        }
    }

    public static void frame(String event, MessageDirection direction, int protocolId, int bytes, String path) {
        if (!ENABLED)
            return;
        log("event=" + event + " direction=" + direction + " layer=frame protocolId=" + protocolId + " frameBytes="
            + bytes + " path=" + path);
    }

    public static void selection(String event, String side, int subject, long intent, int dictionary, int values,
        String reason) {
        if (!ENABLED)
            return;
        log("event=" + event + " side=" + side + " layer=selection subject=" + subject + " intent=" + intent
            + " dictionary=" + dictionary + " values=" + values + " reason=" + reason);
    }

    /** Callers guard this legacy vehicle observation before constructing its arguments. */
    public static void trace(String event, Object... arguments) {
        if (ENABLED)
            log("event=" + event + " " + java.util.Arrays.toString(arguments));
    }

    private static String summary(ProtoMessage<?> message) {
        if (message instanceof SelectionChangeProto value && value.hasSelection())
            return selection(value.selectionUnsafe());
        if (message instanceof EntityTrackProto value && value.hasSelection())
            return selection(value.selectionUnsafe()) + " mode=full";
        if (message instanceof SelectionStateSyncProto value)
            return " subject=" + value.entityId() + " intent=" + value.intentId() + " mode="
                + (value.hasState() ? "delta" : value.hasSync() ? "sync" : "absent") + " dictionary=0 values="
                + (value.hasState() && value.state().hasRoaming() ? value.state().roamingUnsafe().values_().size() : 0)
                + " arguments=" + (value.hasSync() ? value.sync().arguments().size() : 0);
        if (message instanceof EntityStateSyncProto value)
            return " subject=" + value.entityId() + " mode=delta";
        if (message instanceof PlayerFlagsProto value)
            return " subject=" + value.entityId();
        return "";
    }

    private static String selection(SelectionProto value) {
        return " subject=" + value.entityId() + " intent=" + value.intentId() + " dictionary="
            + value.roamingNames().size() + " values=" + (value.hasState() && value.stateUnsafe().hasRoaming()
                ? value.stateUnsafe().roamingUnsafe().values_().size() : 0);
    }

    private static void log(String detail) {
        try {
            LOGGER.info("YSM_NETWORK " + detail);
        } catch (RuntimeException ignored) {
            // Logging must not become a second failure authority.
        }
    }
}
